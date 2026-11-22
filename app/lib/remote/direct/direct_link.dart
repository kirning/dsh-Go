import 'dart:async';

import 'package:dsh_client/dsh_client.dart';

import '../link_status.dart';
import 'direct_host_config.dart';

/// Logs in to the `dsh web` behind a fixed address. Overridable so tests can
/// inject a fake client.
typedef DirectClientConnector =
    Future<DshClient> Function({required Uri baseUri, required String token, required String hostHeader});

Future<DshClient> _defaultConnector({
  required Uri baseUri,
  required String token,
  required String hostHeader,
}) => DshClient.connect(baseUri: baseUri, token: token, hostHeader: hostHeader);

/// A host already running `dsh web` at a known address on the network.
///
/// No SSH, no relay: `DshClient` calls the server over plain HTTP with the
/// `Host` header the server's fence expects. The launch token changes every
/// time `dsh web` restarts, so the token is loaded lazily on each attempt and
/// a 401 sends the person back to the form instead of retrying forever.
class DirectHostLink implements HostLink {
  DirectHostLink({
    required this.config,
    required this.loadToken,
    DirectClientConnector? connector,
    Stream<void>? kicks,
    Backoff? backoff,
    this.keepAliveInterval = const Duration(seconds: 20),
  }) : _connectClient = connector ?? _defaultConnector,
       _backoff = backoff ?? Backoff() {
    _kickSub = kicks?.listen((_) => kick());
  }

  /// The address this link was built for. Renames apply through [updateConfig];
  /// a new address or port rebuilds the link.
  DirectHostConfig config;
  final Future<String?> Function() loadToken;
  final DirectClientConnector _connectClient;
  final Backoff _backoff;
  StreamSubscription<void>? _kickSub;

  /// How often a live link checks that its socket is still up.
  final Duration keepAliveInterval;

  final _statuses = StreamController<HostLinkStatus>.broadcast(sync: true);
  final _clients = StreamController<DshClient>.broadcast();
  final _delay = InterruptibleDelay();

  HostLinkStatus _status = HostLinkStatus.idle;
  DshClient? _client;
  Future<DshClient>? _opening;
  StreamSubscription<DshConnectionState>? _muxSub;
  Timer? _keepAlive;
  bool _closed = false;
  bool _recovering = false;
  bool _needsRelogin = false;

  @override
  HostLinkStatus get status => _status;

  @override
  DshClient? get currentClient => _status.isConnected ? _client : null;

  @override
  Stream<HostLinkStatus> get statuses => _statuses.stream;

  @override
  Stream<DshClient> get clients => _clients.stream;

  /// A rename without reconnecting.
  void updateConfig(DirectHostConfig next) {
    config = config.copyWith(name: next.name);
  }

  void _emit(HostLinkStatus next) {
    if (_closed && next.phase != HostLinkPhase.idle) return;
    _status = next;
    _statuses.add(next);
  }

  @override
  Future<DshClient> open() {
    if (_closed) return Future.error(StateError('link closed'));
    final client = _client;
    if (client != null && _status.isConnected) return Future.value(client);
    return _opening ??= _openFirst().whenComplete(() => _opening = null);
  }

  Future<DshClient> _openFirst() async {
    _backoff.reset();
    _emit(const HostLinkStatus(HostLinkPhase.connecting, step: HostLinkStep.connect));
    try {
      return await _connectOnce();
    } on HostLinkError catch (e) {
      if (!e.retryable) {
        _fail(e);
        rethrow;
      }
      return _recover(e);
    } catch (e) {
      final error = HostLinkError(HostLinkErrorKind.other, '连接失败', detail: '$e', retryable: true);
      return _recover(error);
    }
  }

  Future<DshClient> _connectOnce() async {
    _checkOpen();
    _emit(const HostLinkStatus(HostLinkPhase.connecting, step: HostLinkStep.connect));
    final token = (await loadToken())?.trim() ?? '';
    if (token.isEmpty) {
      throw const HostLinkError(HostLinkErrorKind.loginFailed, '这台主机还没填 token', retryable: false);
    }
    final existing = _client;
    final DshClient client;
    try {
      client = await _connectClient(
        baseUri: config.baseUri,
        token: token,
        hostHeader: config.authority,
      );
    } on DshHttpException catch (e) {
      final rejected = e.statusCode == 401 || e.statusCode == 403;
      throw HostLinkError(
        rejected ? HostLinkErrorKind.loginFailed : HostLinkErrorKind.unreachable,
        rejected ? 'token 不对，或者主机不接受这个 Host' : '连不上这台 DSH',
        detail: '$e',
        retryable: !rejected,
      );
    } on DshException catch (e) {
      throw HostLinkError(HostLinkErrorKind.unreachable, '连不上这台 DSH', detail: '$e', retryable: true);
    } on Object catch (e) {
      // SocketException, TimeoutException, a bad address…
      throw HostLinkError(HostLinkErrorKind.unreachable, '连不上这台 DSH', detail: '$e', retryable: true);
    }
    if (_closed) {
      await client.close().catchError((_) {});
      throw StateError('link closed');
    }
    _needsRelogin = false;
    _client = client;
    _watchMux(client);
    if (existing != null && !identical(existing, client)) {
      _clients.add(client);
      unawaited(existing.close().catchError((_) {}));
    }
    _checkOpen();
    _emit(
      HostLinkStatus(
        HostLinkPhase.connected,
        completed: HostLinkStep.values.toSet(),
        detail: '直连 ${config.authority}',
      ),
    );
    _startKeepAlive();
    return client;
  }

  void _watchMux(DshClient client) {
    unawaited(_muxSub?.cancel());
    _muxSub = client.mux.states.listen((state) {
      if (!identical(client, _client)) return;
      if (state == DshConnectionState.unauthorized) {
        _needsRelogin = true;
        _lost(const HostLinkError(HostLinkErrorKind.loginFailed, '登录已失效，重新填 token', retryable: false));
      }
    });
  }

  void _fail(HostLinkError error) {
    _teardown();
    _emit(HostLinkStatus(HostLinkPhase.failed, step: _status.step, completed: _status.completed, error: error));
  }

  void _teardown() {
    _keepAlive?.cancel();
    _keepAlive = null;
  }

  void _checkOpen() {
    if (_closed) throw StateError('link closed');
  }

  void _startKeepAlive() {
    _keepAlive?.cancel();
    _keepAlive = Timer.periodic(keepAliveInterval, (_) => _keepAliveTick());
  }

  void _keepAliveTick() {
    if (_closed || _status.phase != HostLinkPhase.connected) return;
    final mux = _client?.mux.state;
    if (mux == DshConnectionState.closed || mux == DshConnectionState.unauthorized) {
      _lost(const HostLinkError(HostLinkErrorKind.unreachable, '连接断了', retryable: true));
    }
  }

  void _lost(HostLinkError reason) {
    if (_closed || _recovering || _status.phase != HostLinkPhase.connected) return;
    _teardown();
    unawaited(_recover(reason).then((_) {}, onError: (_) {}));
  }

  Future<DshClient> _recover(HostLinkError reason) async {
    _recovering = true;
    var last = reason;
    try {
      while (!_closed) {
        if (_backoff.exhausted) {
          final error = HostLinkError(
            last.kind,
            last.message,
            detail: [if (last.detail != null) last.detail!, '已重试 ${_backoff.attempt} 次'].join('\n'),
            retryable: true,
          );
          _fail(error);
          throw error;
        }
        final delay = _backoff.next();
        _emit(
          HostLinkStatus(
            HostLinkPhase.reconnecting,
            attempt: _backoff.attempt,
            error: last,
            nextRetryAt: DateTime.now().add(delay),
          ),
        );
        await _delay.wait(delay);
        if (_closed) break;
        try {
          final client = await _connectOnce();
          _backoff.reset();
          return client;
        } on HostLinkError catch (e) {
          if (_closed) break;
          if (!e.retryable) {
            _fail(e);
            rethrow;
          }
          last = e;
        } catch (e) {
          if (_closed) break;
          last = HostLinkError(HostLinkErrorKind.other, '连接失败', detail: '$e', retryable: true);
        }
      }
      throw StateError('link closed');
    } finally {
      _recovering = false;
    }
  }

  /// Drops the client as if the network went away (live tests / kick).
  void debugDropClient() => _lost(const HostLinkError(HostLinkErrorKind.unreachable, '连接断了', retryable: true));

  @override
  void kick() {
    if (_closed) return;
    switch (_status.phase) {
      case HostLinkPhase.connected:
        unawaited(() async {
          final mux = _client?.mux;
          final alive = mux != null && mux.state == DshConnectionState.connected;
          if (!alive) {
            _backoff.reset();
            _lost(const HostLinkError(HostLinkErrorKind.unreachable, '网络变了，连接断了', retryable: true));
          }
        }());
      case HostLinkPhase.reconnecting:
        _delay.skip();
      case HostLinkPhase.idle:
      case HostLinkPhase.connecting:
      case HostLinkPhase.failed:
        break;
    }
  }

  @override
  void retry() {
    if (_closed) return;
    _backoff.reset();
    _delay.skip();
    if (_status.phase == HostLinkPhase.failed || _status.phase == HostLinkPhase.idle) {
      unawaited(open().then((_) {}, onError: (_) {}));
    }
  }

  @override
  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    _delay.skip();
    _keepAlive?.cancel();
    _keepAlive = null;
    await _muxSub?.cancel();
    await _kickSub?.cancel();
    final client = _client;
    _client = null;
    if (client != null) await client.close().catchError((_) {});
    await _statuses.close();
    await _clients.close();
  }
}
