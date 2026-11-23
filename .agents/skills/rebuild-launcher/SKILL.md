---
name: rebuild-launcher
description: Rebuild and restart the Saiku launcher fat-JAR after changing saiku-ui or Java code, without shipping stale UI bundles or breaking the running JVM. Use when asked to run, rebuild, or restart Saiku locally, or when a UI change "doesn't show up" in the launcher.
---

# Rebuild the launcher

Two traps make a naive `mvn package` go wrong here; this sequence avoids both.

1. **Stop any running launcher first.** Its JVM holds the fat-JAR open. `mvn clean` deletes it, and Mondrian's Janino `ServiceLoader` then fails with `NoSuchFileException`, so every MDX discover/query returns HTTP 500 until you restart.
   ```bash
   pkill -f 'saiku-launcher/target/saiku-.*\.jar' || true
   ```
2. **Clean the webapp and launcher modules.** The war overlay never purges old hashed SvelteKit bundles (`_app/immutable/...<hash>.js`), so an incremental package ships old and new side by side.
   ```bash
   mvn -pl saiku-webapp,saiku-launcher clean
   ```
3. **Build the fat-JAR.** This also runs `npm ci` + `npm run build` for `saiku-ui` through the frontend plugin.
   ```bash
   mvn -pl saiku-launcher -am -Dmaven.test.skip=true package
   ```
4. **Run it.**
   ```bash
   VERSION=$(mvn -q -DforceStdout help:evaluate -Dexpression=project.version)
   SAIKU_ALLOW_DEFAULT_ADMIN=true java -jar saiku-launcher/target/saiku-$VERSION.jar serve --port 8080 --home ./saiku-home
   ```
   UI at http://localhost:8080/ui/, REST at http://localhost:8080/rest/saiku/api/, login `admin` / `admin`. Without `SAIKU_ALLOW_DEFAULT_ADMIN=true` (or `SAIKU_DEMO=true`, or a real `SAIKU_ADMIN_PASSWORD`) the launcher refuses to boot on the default password.

If you changed the FoodMart seed schema (`saiku-launcher/src/main/resources/seed/FoodMart4.xml`), an existing `saiku-home` won't pick it up. Delete `saiku-home/data/FoodMart4.xml` before relaunching.
