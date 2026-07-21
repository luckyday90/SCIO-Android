# Protocollo sperimentale implementato

- Comando caratteristica: `00003492-0000-1000-8000-00805f9b34fb`
- Scansione: `01 BA 02 00 00`
- Temperatura: `01 BA 04 00 00`
- Batteria: `01 BA 05 00 00`
- Preparazione riferimento bianco: `01 BA 0E 00 00` (significato non definitivamente confermato)

La notifica viene scelta automaticamente tra le caratteristiche NOTIFY/INDICATE, preferendo quella nello stesso servizio della caratteristica comando. Tutti gli UUID scoperti sono mostrati nell'interfaccia per permettere diagnosi manuale.
