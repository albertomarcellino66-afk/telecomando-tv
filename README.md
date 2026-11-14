# Telecomando TV

App Android semplice per comandare il televisore Samsung del salotto
(QE43Q60AAUXZT, IP fisso 192.168.178.47) dal telefono, via Wi-Fi.

Tasti: accensione, volume ±, muto, canale ±, tastierino numerico,
frecce + OK, indietro, home, Netflix, Prime Video, YouTube.

- Collegamento: WebSocket cifrato `wss://192.168.178.47:8002` (protocollo telecomando Samsung Tizen).
- Alla prima apertura il televisore chiede di autorizzare "Telecomando".
- Accensione da spento: Wake-on-LAN sul MAC 64:07:F6:AF:C1:A8
  (sul TV deve essere attiva l'opzione "Accendi con dispositivo mobile").
- L'APK viene compilato da GitHub Actions a ogni push su `main`
  (scheda Actions → ultima esecuzione → artefatto "TelecomandoTV").
