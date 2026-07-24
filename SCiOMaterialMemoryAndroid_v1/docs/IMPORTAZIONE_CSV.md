# Importazione CSV SCiO

La versione 1.1.0 importa e conserva nel database locale due varianti di export SCiO.

## Export con colonne band

La riga di intestazione deve contenere almeno le 331 colonne da `band740` a
`band1070`. Ogni riga successiva diventa un record spettrale; le altre colonne
sono conservate come metadati.

## Export sviluppatore

Il file può iniziare con il preambolo SCiO (`num_records`,
`num_wavelengths`, `wavelengths_start` e altri campi). L'intestazione deve
contenere tutti e tre i blocchi:

- `spectrum_740.0` … `spectrum_1070.0`;
- `wr_raw_740.0` … `wr_raw_1070.0`;
- `sample_raw_740.0` … `sample_raw_1070.0`.

L'eventuale riga con i tipi (`int`, `unicode`, `float` e simili) viene ignorata.
Campi CSV tra virgolette, virgole nei metadati, CRLF e BOM UTF-8 sono gestiti.

## Uso

Aprire **Dati e log**, scegliere **Importa CSV** e selezionare il file. La
schermata mostra file, formato, numero di record, intervallo spettrale e gruppi.
Il contenuto completo è incluso nella successiva esportazione JSON.

Il limite per singolo file è 25 MB. Un hash SHA-256 impedisce di importare due
volte lo stesso contenuto. Gli spettri importati sono separati dalle acquisizioni
BLE grezze, così il confronto sperimentale esistente non interpreta valori
calibrati come pacchetti GATT.
