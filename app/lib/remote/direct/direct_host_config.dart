import 'dart:math' as math;

/// A `dsh web` that is already listening at a fixed address: the phone talks
/// HTTP to it directly. No SSH hop, no relay, no tunnel.
///
/// The server side has to cooperate: `dsh web --host <this address>` (the bind
/// address is accepted by the Host fence), and the address has to be reachable
/// on the network the phone is on. The launch token comes from the `?token=`
/// line the server prints at startup.
class DirectHostConfig {
  const DirectHostConfig({required this.id, required this.name, required this.host, required this.port});

  final String id;
  final String name;

  /// IPv4 or IPv6 literal, or a hostname the phone can resolve. IPv6 without
  /// brackets.
  final String host;
  final int port;

  static const defaultPort = 3080;

  /// Where the launch token lives in the [SecretStore].
  static String tokenKey(String hostId) => 'direct.$hostId.token';

  /// `host:port` exactly as the Host fence sees it (IPv6 keeps its brackets).
  String get authority => '${host.contains(':') ? '[$host]' : host}:$port';

  Uri get baseUri => Uri(scheme: 'http', host: host, port: port);

  /// Identity of the connection target: a new address or port is a new link.
  String get connectionKey => '$host:$port';

  DirectHostConfig copyWith({String? name, String? host, int? port}) =>
      DirectHostConfig(id: id, name: name ?? this.name, host: host ?? this.host, port: port ?? this.port);

  Map<String, Object?> toJson() => {'id': id, 'name': name, 'host': host, 'port': port};

  static DirectHostConfig? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final id = raw['id'];
    final host = raw['host'];
    if (id is! String || id.isEmpty || host is! String || host.trim().isEmpty) return null;
    final rawPort = raw['port'];
    final port = rawPort is int ? rawPort : int.tryParse('$rawPort');
    if (port == null || port <= 0 || port > 65535) return null;
    final rawName = raw['name'];
    final name = rawName is String && rawName.trim().isNotEmpty ? rawName.trim() : host.trim();
    return DirectHostConfig(id: id, name: name, host: host.trim(), port: port);
  }

  static String newId([math.Random? random]) {
    final r = random ?? math.Random.secure();
    return List.generate(8, (_) => r.nextInt(256).toRadixString(16).padLeft(2, '0')).join();
  }

  /// Strips the brackets a person may paste around an IPv6 literal.
  static String bareHost(String input) {
    var value = input.trim();
    if (value.startsWith('[') && value.endsWith(']')) value = value.substring(1, value.length - 1);
    return value;
  }

  @override
  bool operator ==(Object other) =>
      other is DirectHostConfig &&
      other.id == id &&
      other.name == name &&
      other.host == host &&
      other.port == port;

  @override
  int get hashCode => Object.hash(id, name, host, port);
}
