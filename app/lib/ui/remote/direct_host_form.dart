import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../remote/direct/direct_host_config.dart';
import '../../state/hosts_state.dart';
import '../common/app_dialog.dart';
import '../providers/provider_widgets.dart' show FormFieldBlock, FormFieldGroup;
import '../settings/settings_widgets.dart';
import '../shell/page_frame.dart';
import '../theme/theme.dart';

/// Add or edit a host that already runs `dsh web` at a fixed address: the
/// phone connects to it directly, without SSH and without the relay.
class DirectHostFormPage extends ConsumerStatefulWidget {
  const DirectHostFormPage({super.key, this.existing});

  final DirectHostConfig? existing;

  @override
  ConsumerState<DirectHostFormPage> createState() => _DirectHostFormPageState();
}

class _DirectHostFormPageState extends ConsumerState<DirectHostFormPage> {
  late final TextEditingController _name;
  late final TextEditingController _host;
  late final TextEditingController _port;
  final _token = TextEditingController();

  bool _obscure = true;
  bool _saving = false;
  String? _hostError;
  String? _portError;

  bool get _editing => widget.existing != null;

  @override
  void initState() {
    super.initState();
    final existing = widget.existing;
    _name = TextEditingController(text: existing?.name ?? '');
    _host = TextEditingController(text: existing?.host ?? '');
    _port = TextEditingController(text: '${existing?.port ?? DirectHostConfig.defaultPort}');
    if (existing != null) {
      unawaited(
        ref
            .read(hostsProvider.notifier)
            .loadDirectToken(existing.id)
            .then((value) {
              if (value != null && mounted) _token.text = value;
            })
            .catchError((_) {}),
      );
    }
  }

  @override
  void dispose() {
    _name.dispose();
    _host.dispose();
    _port.dispose();
    _token.dispose();
    super.dispose();
  }

  bool _validate() {
    final host = DirectHostConfig.bareHost(_host.text);
    final port = int.tryParse(_port.text.trim());
    final hostError = host.isEmpty ? '填 IP 或主机名' : (host.contains(' ') ? '地址里有空格' : null);
    final portError = (port == null || port <= 0 || port > 65535) ? '端口要在 1–65535 之间' : null;
    setState(() {
      _hostError = hostError;
      _portError = portError;
    });
    return hostError == null && portError == null;
  }

  Future<void> _save() async {
    if (_saving || !_validate()) return;
    setState(() => _saving = true);
    final host = DirectHostConfig.bareHost(_host.text);
    final port = int.parse(_port.text.trim());
    final name = _name.text.trim();
    final config = DirectHostConfig(
      id: widget.existing?.id ?? DirectHostConfig.newId(),
      name: name.isEmpty ? host : name,
      host: host,
      port: port,
    );
    final token = _token.text.trim();
    final ok = await ref.read(hostsProvider.notifier).saveDirect(config, token: token.isEmpty ? null : token);
    if (!mounted) return;
    setState(() => _saving = false);
    if (!ok) {
      await showAppDialog<void>(
        context: context,
        builder: (dialog) => AlertDialog(
          title: const Text('保存失败'),
          content: const Text('这台手机写不进配置，稍后再试。'),
          actions: [TextButton(onPressed: () => Navigator.pop(dialog), child: const Text('知道了'))],
        ),
      );
      return;
    }
    Navigator.of(context).pop(config);
  }

  Future<void> _delete() async {
    final existing = widget.existing;
    if (existing == null) return;
    final ok = await showAppDialog<bool>(
      context: context,
      builder: (dialog) => AlertDialog(
        title: Text('删除「${existing.name}」？'),
        content: const Text('只删这台手机上保存的地址和 token。'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(dialog, false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(dialog, true), child: const Text('删除')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    await ref.read(hostsProvider.notifier).removeDirect(existing.id);
    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final mono = AppType.mono(size: 15, color: Theme.of(context).colorScheme.onSurface, height: 1.4);
    return AppPage.list(
      title: _editing ? '编辑直连主机' : '添加直连主机',
      subtitle: _editing ? null : const Text('那台电脑已经在跑 dsh web，这里填它的地址就能直连，不用 SSH。'),
      children: [
        SettingsSection(
          title: '地址',
          footer: const Text('那台电脑要用 dsh web --host <这个 IP> --port <这个端口> 启动；只绑 127.0.0.1 的话手机连不上。'),
          children: [
            FormFieldGroup(
              children: [
                FormFieldBlock(
                  label: '名称',
                  child: TextField(
                    controller: _name,
                    textInputAction: TextInputAction.next,
                    decoration: const InputDecoration(hintText: '比如：书房的电脑'),
                  ),
                ),
                FormFieldBlock(
                  label: 'IP / 主机名',
                  error: _hostError,
                  child: TextField(
                    controller: _host,
                    keyboardType: TextInputType.url,
                    autocorrect: false,
                    textInputAction: TextInputAction.next,
                    style: mono,
                    onChanged: (_) {
                      if (_hostError != null) setState(() => _hostError = null);
                    },
                    decoration: InputDecoration(
                      hintText: '192.168.1.23',
                      error: _hostError == null ? null : const SizedBox.shrink(),
                    ),
                  ),
                ),
                FormFieldBlock(
                  label: '端口',
                  error: _portError,
                  child: TextField(
                    controller: _port,
                    keyboardType: TextInputType.number,
                    inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                    textInputAction: TextInputAction.next,
                    style: mono,
                    onChanged: (_) {
                      if (_portError != null) setState(() => _portError = null);
                    },
                    decoration: InputDecoration(error: _portError == null ? null : const SizedBox.shrink()),
                  ),
                ),
              ],
            ),
          ],
        ),
        SettingsSection(
          title: 'Token',
          footer: const Text('在电脑上启动 dsh web 时打印的链接里，问号后面那串就是 token。它每次重启都会变，变了要回来改一次。'),
          children: [
            FormFieldGroup(
              children: [
                FormFieldBlock(
                  label: '启动 token',
                  child: TextField(
                    controller: _token,
                    obscureText: _obscure,
                    autocorrect: false,
                    enableSuggestions: false,
                    style: mono,
                    decoration: InputDecoration(
                      hintText: '从 dsh web 的启动日志里复制',
                      suffixIcon: IconButton(
                        tooltip: _obscure ? '显示' : '隐藏',
                        onPressed: () => setState(() => _obscure = !_obscure),
                        icon: Icon(_obscure ? Icons.visibility_outlined : Icons.visibility_off_outlined),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              FilledButton(
                onPressed: _saving ? null : _save,
                child: Text(_saving ? '保存中…' : (_editing ? '保存' : '添加')),
              ),
              if (_editing) ...[
                const SizedBox(height: 8),
                TextButton(onPressed: _delete, child: const Text('删除这台主机')),
              ],
            ],
          ),
        ),
      ],
    );
  }
}
