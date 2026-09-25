# How to get the APK on your phone (no coding)

You don't need to install anything on a computer. GitHub builds the APK for you in the
cloud, free. ~10 minutes the first time.

## One-time: put the project on GitHub
1. Create a free account at https://github.com (skip if you have one).
2. Click **New repository** → name it `euro-call` → keep it **Private** → **Create**.
3. On the new repo page, click **uploading an existing file**.
4. Unzip `EuroCall-android-app.zip` on your computer, then drag **all the files and
   folders inside the `EuroCall` folder** into the upload box (including the hidden
   `.github` folder — if you don't see it, on Windows enable "Hidden items" in File
   Explorer's View menu; on Mac press Cmd+Shift+. to show hidden files).
5. Click **Commit changes**.

## The robot builds it
6. Click the **Actions** tab at the top of your repo.
7. You'll see a run called **Build APK** (it starts automatically). Wait for the green ✓
   (about 3–5 minutes).
8. Click into that run → scroll to **Artifacts** → download **EuroCall-debug-apk**.
   It downloads a `.zip`; unzip it to get **app-debug.apk**.

## Install on your phone
9. Send `app-debug.apk` to your phone (email it to yourself, WhatsApp it, or USB copy).
10. Tap the file → Android asks to allow installing from this source → **Allow** → **Install**.
11. Open **EURO Call**, grant the permissions, and set your backend URL.

## Making changes later (still no coding)
Whenever you (or an AI) change the code, upload the changed files to the same repo →
the Actions tab builds a fresh APK automatically → download and reinstall. That's it.

---

### Easier alternative with a nicer interface: Codemagic
If GitHub feels fiddly, https://codemagic.io has a visual interface: connect the GitHub
repo, pick "Android", press **Start build**, and it emails you the APK. Free tier is
plenty for personal use.

### Note
This produces a **debug** APK — perfectly fine to install and use yourself. A signed
"release" APK (for wider distribution) needs a keystore, but you don't need that for
your own phone.
