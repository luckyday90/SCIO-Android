# SCiO Material Memory Android 1.0

Applicazione Android nativa sperimentale per acquisire notifiche BLE dallo SCiO, registrare pacchetti grezzi, ricomporre tre sezioni di scansione e costruire una libreria locale di impronte dei materiali.

## Funzioni
- ricerca e connessione BLE;
- diagnostica completa servizi/caratteristiche GATT;
- caratteristica comando SCiO `00003492-0000-1000-8000-00805f9b34fb`;
- comandi scansione `01 BA 02 00 00`, temperatura `01 BA 04 00 00`, batteria `01 BA 05 00 00`;
- riferimento bianco sperimentale con preparazione `01 BA 0E 00 00`;
- registrazione integrale dei pacchetti;
- controllo delle tre sezioni e timeout;
- archivio SQLite dei materiali e delle letture;
- confronto interno delle impronte grezze;
- esportazione JSON;
- compilazione locale con Android Studio oppure online con GitHub Actions.

## Avvertenza
Non è un'app ufficiale Consumer Physics. La conversione proprietaria nei 331 valori spettrali normalizzati non è disponibile. Temperatura, batteria e riferimento bianco devono essere verificati sul dispositivo reale e i byte grezzi restano sempre la fonte primaria.

Leggere `docs/COMPILAZIONE_MAC.md` e `docs/PRIMO_TEST.md`.
