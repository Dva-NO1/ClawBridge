# Third-Party Notices

ClawBridge is derived from and extends the open-source project **[AvenalJ/AntigravityMobile](https://github.com/AvenalJ/AntigravityMobile)**:

- **Upstream Repository**: https://github.com/AvenalJ/AntigravityMobile
- **Upstream Copyright**: Copyright (c) 2026 AvenalJ
- **License**: MIT License (see [LICENSE](./LICENSE))

## Major Architectural Changes in ClawBridge
1. **Self-Hosted WSS Relay (`relay/`)**: Replaced LAN/VPN direct connection with a lightweight single-process Node.js WSS relay supporting 6-digit one-time pairing, SHA-256 hashed device tokens, offline FIFO notification buffering (cap 200), and zero-hardcoding VPS migration.
2. **Windows Desktop Bridge (`bridge/`)**: Added Antigravity Hub ConnectRPC + transcript live mirroring, Codex `app-server` JSON-RPC 2.0 integration with seamless `thread/fork` continuation, independent Codex OpenAI & Antigravity quota polling, silent system tray host (`ClawBridgeDesktop.cs`), hibernate-wake auto-recovery, and risk-classified approval gating.
3. **Android Native Client (`android/`)**: Rebuilt in Kotlin + Jetpack Compose (`minSdk 26`) with foreground service keep-alive, DoH DNS resilience, dual-client 5h/7d remaining-quota gauges, compact searchable session list (Time / Project views), chat scroll-position memory, model switching, bidirectional file/image transfer, and PIN-protected high-risk approvals.
