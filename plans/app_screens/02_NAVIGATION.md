# Navigation & App Shell

## Navigation library

Use **`androidx.navigation:navigation-compose`** — not the fragment-based nav already in [libs.versions.toml](../../gradle/libs.versions.toml). The fragment libs were added when the README assumed Fragments; we keep them as a transitional dep until the screen work is in, then remove. See Q4 in [06_OPEN_QUESTIONS.md](06_OPEN_QUESTIONS.md).

Add to [libs.versions.toml](../../gradle/libs.versions.toml):
```toml
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigation" }
```

## Route graph

```
ROOT
├── route("provisioning")              # First-launch only; no app shell
└── route("app")                       # Hosts AstraAppShell with bottom bar
    ├── route("missions")              # MISSIONS tab — landing
    │   ├── route("missions/{id}")     # Channel list for mission
    │   │   └── route("missions/{id}/channels/{channelId}")  # Secure chat
    ├── route("security")              # SECURITY tab
    ├── route("tools")                 # TOOLS tab
    └── route("panic")                 # PANIC tab
```

Implementation lives in `ux/AstraNavGraph.kt` (new file, Ismail's branch).

```kotlin
@Composable
fun AstraNavGraph(
    navController: NavHostController,
    container: AppContainer,
    startDestination: String,
) {
    NavHost(navController, startDestination) {
        composable("provisioning") { ProvisioningRoute(container, onDone = { navController.navigate("app") { popUpTo("provisioning") { inclusive = true } } }) }
        navigation(startDestination = "missions", route = "app") {
            composable("missions")    { MissionsRoute(container, onMissionClick = { navController.navigate("missions/$it") }) }
            composable("missions/{id}") { backStack -> ChannelListRoute(container, backStack.arguments!!.getString("id")!!) }
            composable("missions/{id}/channels/{channelId}") { backStack -> ChatRoute(container, backStack.arguments!!.getString("id")!!, backStack.arguments!!.getString("channelId")!!) }
            composable("security")    { SecurityRoute(container) }
            composable("tools")       { ToolsRoute(container) }
            composable("panic")       { PanicRoute(container, onWiped = { /* reset to provisioning */ }) }
        }
    }
}
```

## App shell

`AstraAppShell` is the Scaffold that hosts every "app/*" destination. Owns the top bar and bottom bar; the destination Composable fills the body slot.

```kotlin
@Composable
fun AstraAppShell(
    selectedTab: NavTab,
    onTabSelect: (NavTab) -> Unit,
    topBarContent: @Composable () -> Unit = { DefaultAstraTopBar() },
    body: @Composable (PaddingValues) -> Unit,
) { /* Scaffold with AstraTheme.SurfaceDim, 0.dp insets */ }
```

The current [MissionsScreen.kt](../../app/src/main/java/com/explo/capstone/ui/MissionsScreen.kt) inlines its own Scaffold + top bar + bottom bar. **Refactor:** extract those into the shell, leave only the body content in `MissionsRoute`. Phase 2 task.

## Tab → route mapping

| `NavTab` | Route       | Default sub-route |
|----------|-------------|-------------------|
| MISSIONS | `missions`  | `missions` |
| SECURITY | `security`  | `security` |
| TOOLS    | `tools`     | `tools` |
| PANIC    | `panic`     | `panic` |

Tab clicks `popUpTo("app") { saveState = true }` and `restoreState = true` — standard "preserve sub-tab state" pattern.

## Top-bar variants

Most screens share the default top bar (logo + callsign/clearance + profile avatar). Two screens override:

- **Channel list** — adds a back button and the mission name as the title (`OPERATION SILENT SENTINEL`). See [secure_chat/code.html](../../design/stitch_astrasecure_north_star_document/channel_list/code.html) header.
- **Secure chat** — back button + channel name + key-rotation timer. The right-side avatar is replaced by a "verified" lock icon.

Top bars are passed in via the `topBarContent` slot.

## Back-stack rules

- **From identity provisioning to app:** `popUpTo("provisioning") { inclusive = true }`. Pressing back from missions must not return to provisioning.
- **From panic-completed:** clear the entire back stack and navigate to `provisioning`. The user is a new identity.
- **Within a mission:** back from chat → channel list → missions list → exits. Standard Android.
- **Tab switching:** does not clear sibling tab back-stacks; uses `saveState` / `restoreState`.

## Deep links

Out of scope for the capstone. If added later, scope only `missions/{id}` (not chat) — chat deep-links would leak which channel a user reads from logs.

## Window insets & system bars

The app is dark and full-bleed. In [MainActivity.kt](../../app/src/main/java/com/explo/capstone/MainActivity.kt) call `WindowCompat.setDecorFitsSystemWindows(window, false)` and let the Scaffold handle insets. Status-bar icons should be light — set via `WindowInsetsControllerCompat`.

## Activity start logic

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as AstraApp).container
        val start = if (container.identityManager.getUserIdentity() != null) "app" else "provisioning"
        setContent {
            AstraSecureTheme {
                val navController = rememberNavController()
                AstraNavGraph(navController, container, startDestination = start)
            }
        }
    }
}
```

`AstraApp : Application` is a new class — instantiate `AppContainer` once and reuse. Register in [AndroidManifest.xml](../../app/src/main/AndroidManifest.xml) via `android:name=".AstraApp"`.

## Bottom-bar selected-state derivation

Don't pass `selectedTab` as state — derive it from the current `NavBackStackEntry`:

```kotlin
val backStackEntry by navController.currentBackStackEntryAsState()
val selectedTab = NavTab.fromRoute(backStackEntry?.destination?.route)
```

This keeps the bar correct after process death and across deep stacks (chat screen still shows MISSIONS as the active tab).
