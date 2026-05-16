# Telemetry Service

This service can be opened in IntelliJ in two ways:

1. As part of the full backend Maven reactor:

   ```powershell
   cd D:\canary\voltcanary\backend
   mvn -pl services/telemetry-service -am compile
   ```

2. As a standalone IntelliJ project from this folder:

   ```text
   D:\canary\voltcanary\backend\services\telemetry-service
   ```

   Before opening it standalone, install the shared backend libraries into the
   local Maven repository:

   ```powershell
   .\bootstrap-standalone.ps1
   ```

## Why The Bootstrap Is Needed

`telemetry-service` depends on `common-events`:

```xml
<dependency>
    <groupId>com.voltcanary</groupId>
    <artifactId>common-events</artifactId>
    <version>${project.version}</version>
</dependency>
```

When the full backend root is opened, Maven sees `common-events` as a reactor
module. When only this folder is opened, Maven resolves `common-events` from
the local Maven repository instead. The bootstrap script installs that shared
artifact locally.

