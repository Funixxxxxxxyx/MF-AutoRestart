# PlaceholderAPI Support

MF-AutoRestart registers the expansion identifier `mfautorestart`. Use these variables to show countdown updates in other plugins (scoreboards, tab lists, etc.).

---

## Placeholders List

| Placeholder | Return Format | Example (Active) | Example (Inactive) | Description |
| :--- | :--- | :--- | :--- | :--- |
| `%mfautorestart_active%` | Boolean | `true` | `false` | Checks if a countdown sequence is running. |
| `%mfautorestart_seconds_remaining%` | Integer | `120` | `0` | Returns the raw remaining seconds. |
| `%mfautorestart_time_remaining%` | `MM:SS` | `02:00` | `00:00` | Returns remaining countdown formatted as minutes/seconds. |
| `%mfautorestart_time_remaining_formatted%`| String | `2m` or `1m 30s` | *(empty)* | Returns a user-friendly verbal time string. |
| `%mfautorestart_next_restart%` | String | `Sun 07:00` | `None` | Returns the day and time of the next scheduled restart. |
| `%mfautorestart_next_remaining%` | String | `3h 45m` | `None` | Returns time remaining until the next restart. |
