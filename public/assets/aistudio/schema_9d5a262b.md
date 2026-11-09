# Schema dei dati — catalog_stations.csv

File: `catalog_stations.csv` (UTF-8, separato da virgole, quoting standard RFC 4180).
~55.797 stazioni uniche, ~58.212 righe (una stazione può avere più stream → più righe con lo stesso `id`).

## Colonne

| Colonna | Tipo | Descrizione |
|---|---|---|
| `name` | string | Nome della stazione radio (es. "Radio Swiss Jazz") |
| `id` | string | Identificativo univoco stazione (es. "swissjazz"). **Chiave per deduplicare**: una card per `id` |
| `city` | string | Città (può essere vuoto) |
| `country` | string | Paese (es. "USA", "Germany", "Italy") |
| `genres` | string | Generi separati da `;` (es. "Blues;Jazz;Chillout"). **Da splittare su `;` per il filtro** |
| `logo100` | string (URL) | Logo 100×100px HTTPS (può essere vuoto) |
| `logo175` | string (URL) | Logo 175×175px HTTPS — **risoluzione consigliata di default** |
| `logo300` | string (URL) | Logo 300×300px HTTPS (spesso vuoto) |
| `stream_url` | string (URL) | URL diretto dello stream (MP3/AAC/OGG/HLS). HTTPS, riproducibile nel browser |
| `format` | string | `MP3` \| `AAC` \| `OGG` \| `UNKNOWN` |
| `status` | string | `VALID` \| altri. **Usare solo righe con `status=VALID`** |

## Logiche da applicare

1. **Deduplicazione**: per la lista, una sola riga per `id`. Se più righe hanno lo stesso `id`, prendere il primo stream `status=VALID` (in ordine di apparizione).
2. **Filtro genere**: una stazione appartiene al genere G se `genres.split(';')` contiene G.
3. **Logo**: preferire `logo175`; se vuoto, fallback a `logo100`; se vuoto, fallback a `logo300`; se tutti vuoti, placeholder.
4. **Riproduzione**: passare `stream_url` al `src` di `<audio>`. Non serve trasformazione.

## Esempio di riga

```
name,id,city,country,genres,logo100,logo175,logo300,stream_url,format,status
Radio Swiss Jazz,swissjazz,Basel,Switzerland,Blues;Jazz;Chillout,https://station-images-prod.radio-assets.com/100/swissjazz.png?v=...,https://station-images-prod.radio-assets.com/175/swissjazz.png?v=...,,https://stream.srg-ssr.ch/m/rsj/mp3_128,MP3,VALID
```

## Note sui formati di stream
- **MP3** e **AAC**: riproducibili nativamente con `<audio>` in tutti i browser moderni.
- **HLS** (`.m3u8`): richiede `hls.js` o browser con supporto nativo (Safari sì, Chrome no). Trattarli con un fallback: se il formato è `.m3u8` e il browser non lo supporta nativamente, mostrare "Formato non supportato" o caricare hls.js.
- **UNKNOWN**: tentare comunque la riproduzione; se fallisce, gestire l'errore.

## Set di generi
Vedi `genres_unique.txt` (180 generi con conteggio). I più frequenti: Pop, Hits, Rock, 80s, Electro, Top 40 & Charts, Oldies, 90s, Latin, Christian Music, Talk, Country, 70s, Hip Hop, Alternative.

---

## Now-playing (brano in onda) — endpoint live via proxy

Oltre al catalogo (statico), esiste un endpoint **live** per il brano attualmente in onda. L'endpoint diretto (`https://api.radio.de/stations/now-playing?stationIds=...`) ha CORS limitato a `https://www.radio.net`, quindi l'app lo chiama **tramite un proxy serverless** esposto allo stesso dominio dell'app.

### Proxy (usato dal frontend)
```
GET /api/now-playing?stationIds=<id1,id2,...>
```
- Serverless function su Vercel (`api/now-playing.ts`) che fa fetch server-side a `https://api.radio.de/stations/now-playing?stationIds=...`.
- Header risposta: `Access-Control-Allow-Origin: *`, `Cache-Control: public, max-age=10`.
- Risponde `OPTIONS` con 200; `400` se `stationIds` mancante; `[]` se upstream non ok.

### Risposta
Array JSON; una entry per ogni stazione che ha metadata disponibili (le stazioni senza metadata ora-vuota vengono omesse):
```json
[
  {"title":"Artista - Brano","stationId":"swissjazz"}
]
```
- `title`: stringa nel formato `"Artista - Brano"` (già combinato; non c'è campo artista/brano separato).
- `stationId`: l'id della stazione.
- **Non c'è cover art del brano**: per l'immagine si usa il logo della stazione (già nel catalogo).

### Note importanti
- **Polling**: il sito aggiorna ogni ~30 secondi. L'app dovrebbe rifare la chiamata ogni 30s per la stazione in riproduzione.
- **CORS (importante per la versione web)**: l'endpoint restituisce `Access-Control-Allow-Origin: https://www.radio.net` — quindi **chiamate dal browser da un dominio diverso sono bloccate dal CORS**. Per l'app Android (HTTP nativo, no CORS) non c'è problema. Per la web app React servita da un dominio proprio, serve un **proxy backend** (o serverless) che inoltri la chiamata ad `api.radio.de` e la esponga al frontend.
- **Affidabilità**: non tutte le stazioni hanno metadata now-playing; l'entry semplicemente non compare. L'app deve gestire il caso "nessun brano disponibile".
- L'endpoint è una **API interna non documentata** di radio.de/radio.net: può cambiare.
