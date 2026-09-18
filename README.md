# Anker Battery Monitor v0.7

Native Android Views build for CodeAssist/JVM 1.8.

## v0.7 UI
- Futuristic dark/neon dashboard based on the generated transparent sprite-sheet concept.
- Native device cards, metric panels, port tiles, BLE status chips, packet monitor and bottom navigation.
- Includes cropped SOLIX C200(X) and Prime 20K artwork assets from the generated UI kit.
- Keeps the existing read-only BLE scan/connect/telemetry/logger behavior.

The bottom Stats/Tools/Settings items are currently visual placeholders; Home is the implemented screen.

## v1.1 telemetry

The BLE layer now performs the read-only Anker FF09 encrypted session handshake instead of only subscribing to the notify characteristic. SOLIX-family devices use P-256 ECDH with AES-CBC session traffic; Prime-family devices use AES-GCM negotiation/session traffic and the Prime telemetry subscription sequence. The app does not expose device-control operations.

Implemented live decoding includes the Prime 20K/A110B battery percentage, temperature, total output and C1/C2/USB-A port telemetry, plus SOLIX C200(X)/A1725-family battery percentage, temperature, input/output power, time remaining and port power fields.
