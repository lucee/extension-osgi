# Lucee OSGi Extension

OSGi support (Apache Felix) for Lucee 9, moved out of the Lucee core (LDEV-6627).

The extension provides the `lucee.runtime.osgi.BundleService` implementation. The OSGi framework is started when the first bundle is needed, not at startup of Lucee.

* `source/java/src/org/lucee/extension/osgi/OSGiBundleService.java`: the service, registered with `META-INF/services/lucee.runtime.osgi.BundleService`
* `source/java/src/resources/default.properties`: the Felix configuration (boot delegation, system packages), the same as it was in the core

The extension is installed automatically when Lucee needs OSGi (LDEV-6829), extensions can also declare it as a dependency (LDEV-6808).

## Build

```
mvn clean install
```

builds `target/osgi-extension-{version}.lex` (and the `.lite.lex`).
