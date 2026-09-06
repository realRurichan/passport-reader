# Privacy design

1. No network permission is declared.
2. OCR models are bundled with the application.
3. Captured images and parsed fields remain in memory and are cleared when the flow ends.
4. Screens are screenshot-able by explicit design (no `FLAG_SECURE`); nothing is written to storage automatically.
5. Sharing is an explicit user action and uses the system share sheet.
6. Production logs contain state and error categories only, never document content.

