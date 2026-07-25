# Importazione CSV SCiO

La versione 1.1.0 importa e conserva nel database locale tre strutture CSV
presenti negli [esempi `nirs4all-formats`](https://github.com/GBeurier/nirs4all-formats/tree/main/samples/scio).

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

## Tabella asse-valore

Le tabelle verticali con intestazione `wavelength,reflectance` vengono importate
come un singolo spettro. Sono accettati anche `wavelength_nm`, `wavelengths` o
`nm` per l'asse e `spectrum`, `absorbance` o `intensity` per il segnale.

I punti possono essere in qualunque ordine e vengono ordinati per lunghezza
d'onda. L'asse deve contenere almeno due valori positivi e univoci; lunghezze
d'onda e misure devono essere numeri finiti. Questa struttura copre anche
`scio_calibration_plate_Polypen.csv`, il cui intervallo 324–790 nm non viene
forzato sull'asse SCiO 740–1070 nm.

## Uso

Aprire **Dati e log**, scegliere **Importa CSV** e selezionare il file. La
schermata mostra file, formato, numero di record, intervallo spettrale e gruppi.
Il contenuto completo è incluso nella successiva esportazione JSON.

Il limite per singolo file è 25 MB. Un hash SHA-256 impedisce di importare due
volte lo stesso contenuto. Gli spettri importati sono separati dalle acquisizioni
BLE grezze, così il confronto sperimentale esistente non interpreta valori
calibrati come pacchetti GATT.
