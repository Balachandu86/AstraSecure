# MissionMessenger

Capstone project — Lovely Professional University
Team: Tejas Khanna, Sandrani Balachandu, Ismail Alam, Tamminana Yashwanth Sai, Jatin Preet Singh

## Module ownership

| Module | Owner | Responsibility |
|--------|-------|----------------|
| `crypto/` | Tejas | Signal Protocol, MCC key isolation |
| `identity/` | Sandrani | Android Keystore, encrypted document vault |
| `ux/` | Ismail | Fragments, navigation, ViewModels |
| `ui/` | Yashwanth | XML layouts, design system, custom views |
| `metadata/` | Jatin | Padding, batching, timing normalization |
| `shared/` | Everyone | Models.kt — shared data classes, read-only |

Package root: `com.explo.capstone`

## Rules

1. Only edit files inside your own module folder and `shared/`.
2. To change `shared/Models.kt`, discuss with the team first.
3. Branch name = your first name. Always work on your branch.
4. Open a PR to `main` every Friday. Keep PRs small.
5. Copy `.windsurfignore.[yourname]` to `.windsurfignore` on your branch so Windsurf only indexes your module.

## Branch setup (run once after cloning)

```bash
git checkout -b yourname    # replace with your first name
cp .windsurfignore.yourname .windsurfignore
```

## Build

Requires Android Studio Hedgehog or newer, Android SDK 34+, JDK 17.
