# Installation & Deployment

Setting up MF-AutoRestart is quick and straightforward.

---

## Prerequisites

* **Minecraft Server**: Paper, Spigot, or Folia running on Minecraft **1.21.x**.
* **Java VM**: Java Runtime Environment (JRE) version **21** or higher.
* **Network Proxy**: BungeeCord, Waterfall, or Velocity with legacy support active (required only for player redirection).

---

## Installation Steps

1. **Download the Plugin**:
   * Build the project source using Maven (`mvn clean package`) or download the compiled `.jar` from the GitHub Releases tab.
2. **Upload the File**:
   * Drop the compiled `MF-AutoRestart-1.0.jar` into your server's `plugins/` directory.
3. **Start Your Server**:
   * Boot the server to let the plugin unpack default configurations and create its directories.
4. **Configure Settings**:
   * Edit `plugins/MF-AutoRestart/config.yml` to set your desired timezone and scheduling times.
5. **Reload Configs**:
   * Run `/ar reload` in the server console or in-game to apply configurations without restart.
