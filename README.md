# MineChat Server

[![CI status](https://github.com/MineChatPowered/server/actions/workflows/build.yml/badge.svg)](https://github.com/MineChatPowered/server/actions/workflows/build.yml)

This plugin implements the server-side component of the MineChat platform. It lets you chat on a Minecraft server without having to log in to a Minecraft account.

The plugin works by generating temporary codes that players can use to authenticate from the [client](https://github.com/MineChatPowered/client). This helps bridge in-game chat with external MineChat clients.

*Not affiliated with or endorsed by Mojang Studios or Minecraft.*

## Features

- **Client authentication**: MineChat clients authenticate by using the generated link code or by reusing their stored client UUID.
- **Chat broadcast**: In-game chat is broadcast to all connected MineChat clients and vice versa.
- **Persistent Storage**: The plugin stores link codes and client information in ObjectBox, so that data remains between server restarts.
- **Automatic cleanup**: Expired link codes are cleaned up automatically every minute.
- **TLS certificates**: Self-signed certificates are generated programmatically (no external tools required).

## Installation

1. **Requirements**:
   - A server running [Paper](https://papermc.io/) or any of its forks.

2. **Download and install the plugin**:
   - Download the latest release from the [releases](https://github.com/MineChatPowered/server/releases/latest) page.
   - Place the downloaded JAR file into your server's `plugins` directory.

3. **Configure**:
   - On first startup, the plugin will automatically generate a TLS keystore.
   - Edit `plugins/MineChat/config.yml` to configure settings:
     ```yaml
     port: 7632
     tls:
       enabled: true
       keystore: "keystore.p12"
       keystore-password: "change-me"
     ```

4. **Start Your Server**: Start or restart your Paper server to load the MineChat Server Plugin.

## Usage

### In-game: linking your account

1. **Generate a link code**:
   - In-game, run the `/minechat link` command.
   - You will receive a temporary link code in chat. This code is valid for 5 minutes.

2. **Link from the [client](https://github.com/MineChatPowered/client)**:
   - Use the provided code in your MineChat CLI client to authenticate:
     ```bash
     minechat-client <host> --port <port> --link <code>
     ```
   - The default port is 7632 if not specified.
   - This links your MineChat client to your Minecraft account without needing to log in with your Minecraft credentials.

### Moderation commands

| Command | Description |
|---------|-------------|
| `/minechat ban <player> [reason]` | Ban a player from MineChat |
| `/minechat unban <player>` | Remove a ban |
| `/minechat mute <player> [duration] [reason]` | Mute a player (duration in minutes) |
| `/minechat unmute <player>` | Remove a mute |
| `/minechat warn <player> [reason]` | Warn a player |
| `/minechat kick <player> [reason]` | Kick a connected player |
| `/minechat link` | Generate a link code |
| `/minechat reload` | Reload configuration |

## How it works

- **Initial phase**:
  The plugin opens a server socket on port `7632` (or configured port) to listen for connections from MineChat clients.

- **Authentication**:
  - Clients use either a new link code or their stored client UUID to authenticate.
  - Successful authentication triggers in-game notifications (join/leave messages) to all players.

- **Message broadcasting**:
  - The plugin listens for in-game chat events and broadcasts messages to connected clients.
  - Similarly, messages received from clients are broadcast to the Minecraft chat.

## Contributing

Contributions are welcome! Feel free to open [issues](https://github.com/MineChatPowered/server/issues) or submit pull requests.

### Roadmap

- [ ] Allow for messages sent from MineChat clients to be visible to plugins like Discord bridges, such as [DiscordSRV](https://github.com/DiscordSRV/DiscordSRV).
- [X] Allow for configuration, such as port, etc

## License

This project is licensed under the MPL-2.0 license. See the [LICENSE](LICENSE) file for details.
