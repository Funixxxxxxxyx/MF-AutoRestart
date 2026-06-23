# Developer Java API & Custom Events

MF-AutoRestart provides a public Java API and custom Bukkit lifecycle events that allow other plugins to query the restart state, programmatically trigger/cancel restarts, or safely intercept server shutdowns.

---

## 📦 1. Dependency Integration

Add MF-AutoRestart to your project classpath.

### Maven (`pom.xml`)
```xml
<dependencies>
    <dependency>
        <groupId>id.funix</groupId>
        <artifactId>MF-AutoRestart</artifactId>
        <version>1.0</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

### Bukkit Manifest (`plugin.yml`)
To ensure your plugin loads after MF-AutoRestart, add it as a soft-dependency:
```yaml
softdepend: [MF-AutoRestart]
```

---

## 🔑 2. Accessing the Service API

MF-AutoRestart registers the `MFAutoRestartAPI` interface into the Bukkit `ServicesManager`. 

### Java Example: Hooking the API
```java
import id.funix.mFAutoRestart.api.MFAutoRestartAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

public class RestartHook {

    private MFAutoRestartAPI api;

    public void register() {
        if (Bukkit.getPluginManager().isPluginEnabled("MF-AutoRestart")) {
            RegisteredServiceProvider<MFAutoRestartAPI> provider = 
                Bukkit.getServicesManager().getRegistration(MFAutoRestartAPI.class);
            
            if (provider != null) {
                this.api = provider.getProvider();
                Bukkit.getLogger().info("Successfully hooked into MF-AutoRestart API!");
            }
        }
    }

    public MFAutoRestartAPI getAPI() {
        return api;
    }
}
```

### Methods Reference

| Method | Return Type | Description |
| :--- | :--- | :--- |
| `isRestartActive()` | `boolean` | Returns `true` if a restart countdown is currently running. |
| `getSecondsRemaining()` | `Optional<Integer>` | Returns the remaining countdown seconds, or `Optional.empty()` if inactive. |
| `getNextScheduledRestart()` | `ZonedDateTime` | Returns the next scheduled daily/weekly restart time, or `null` if none configured. |
| `startRestart(int seconds, boolean manual)` | `boolean` | Starts a restart countdown. Returns `false` if already active. |
| `cancelRestart()` | `boolean` | Cancels the active restart countdown. Returns `false` if none is active. |

---

## 🔔 3. Custom Bukkit Events

Other plugins can listen to lifecycle transitions using Bukkit's standard listener framework (`@EventHandler`).

### A. `AutoRestartStartEvent` (Cancellable)
Fired when a restart sequence is initiated. If cancelled, the countdown will not start.

* **Trigger**: Automatic scheduler, `/ar start`, or API method `startRestart()`.
* **Methods**:
  * `getDurationSeconds()`: returns the starting duration.
  * `isManual()`: returns `true` if manually triggered, `false` if scheduled.

```java
import id.funix.mFAutoRestart.api.event.AutoRestartStartEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class RestartListener implements Listener {

    @EventHandler
    public void onRestartStart(AutoRestartStartEvent event) {
        // Cancel automated restarts if a minigame is actively running
        if (!event.isManual() && isMinigameActive()) {
            event.setCancelled(true);
            org.bukkit.Bukkit.broadcastMessage("§cScheduled restart postponed due to active match!");
        }
    }
}
```

### B. `AutoRestartCancelEvent`
Fired when an active countdown is cancelled.

* **Trigger**: `/ar cancel` or API method `cancelRestart()`.
* **Methods**:
  * `getCancelledBy()`: returns the initiator's name (e.g. `"Console"` or a player's name).

```java
import id.funix.mFAutoRestart.api.event.AutoRestartCancelEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class RestartCancelListener implements Listener {

    @EventHandler
    public void onRestartCancel(AutoRestartCancelEvent event) {
        // Resume task loops that were paused during the countdown
        resumeServerActivities();
    }
}
```

### C. `AutoRestartPreShutdownEvent` (Cancellable)
Fired at `0s` remaining in the countdown, immediately before BungeeCord redirection and server halt.
* **Trigger**: The countdown reaching 0 seconds.
* **Use Case**: Best used to save player inventories, save database records, or close active socket connections.
* **Cancellation**: If cancelled, the redirection is aborted, the countdown is terminated, and the server remains online.

```java
import id.funix.mFAutoRestart.api.event.AutoRestartPreShutdownEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class ShutdownListener implements Listener {

    @EventHandler
    public void onPreShutdown(AutoRestartPreShutdownEvent event) {
        try {
            // Save custom database tables before the server turns off
            CustomDatabase.saveAll();
        } catch (Exception e) {
            // Saving failed: abort restart sequence to prevent player data loss!
            event.setCancelled(true);
            org.bukkit.Bukkit.getLogger().severe("CRITICAL: Failed to save data. Aborted restart.");
        }
    }
}
```
