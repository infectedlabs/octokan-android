# octokan-android

In-app support chat for Android (Kotlin, Jetpack Compose). Messages from your users land in your Octokan inbox, and your
replies appear in the app within seconds. Android 7.0 (API 24) and up.

## One-step setup

From your Android project's folder (needs Node.js):

```bash
npx @octokan/cli init <app key> <backend url>
```

Both values are on the **Install** page of your Octokan dashboard. The command adds the JitPack repository and this
dependency to Gradle and calls `Octokan.configure` from your `Application` class (it creates one if you have none).

## Manual setup

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { maven("https://jitpack.io") } }

// app/build.gradle.kts
dependencies { implementation("com.github.infectedlabs:octokan-android:0.1.0") }

// Application.onCreate
Octokan.configure(this, appId = "app_xxxxxxxxxxxx", baseUrl = "https://YOUR-BACKEND")

// anywhere
Octokan.open(context)        // full screen, or embed the OctokanChat() composable
Octokan.identify(id = user.id, email = user.email, name = user.name)   // optional
Octokan.reset()              // on sign-out
```

Works from Java too (`Octokan.configure(...)`, `Octokan.open(...)` are static).

Nothing is sent or stored until the person opens the chat. Messages queue offline and send themselves later.
