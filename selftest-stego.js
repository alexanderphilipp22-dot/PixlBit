/**
 * Selbsttest des Pixel-Betts (STEGO CORE v2) aus assets/index.html.
 *
 * Er prueft den kompletten Weg, der im Fehlerfall kaputt war:
 *   Nutzlast -> Kopf (Magic/Version/Modus/Laenge/CRC16) -> LSB-Einbettung
 *            -> LSB-Auslesung -> Nutzlast
 * ohne Canvas, ohne DOM, ohne Geraet - die Kernfunktionen sind reine Funktionen
 * ueber Pixel-Arrays und laufen deshalb direkt auf der JVM (Node).
 *
 * Kern-Regression: Ein dunkles/graues Traegerbild darf den Modus NICHT mehr
 * verwirren. Frueher wurde der Modus aus dem Bildinhalt geraten
 * ("erste 100 Pixel grau?") - genau das liess die Entschluesselung mit
 * "Passwort falsch" scheitern.
 *
 * Aufruf:  node selftest-stego.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const htmlPath = path.join(__dirname, 'PixlBit-Android', 'app', 'src', 'main', 'assets', 'index.html');
const html = fs.readFileSync(htmlPath, 'utf8');
const match = html.match(/JAVASCRIPT SYSTEM ENGINE -->\s*<script>([\s\S]*?)<\/script>/);
if (!match) {
    console.error('FEHLER: <script>-Block der Stego-Engine nicht gefunden.');
    process.exit(1);
}
const script = match[1];

// --- Minimal-Stubs: nur so viel, wie das Skript beim Laden beruehrt. ---
const elements = {};
function makeEl(id) {
    const el = {
        id,
        value: '',
        textContent: '',
        className: '',
        classList: { _s: new Set(), add(...c) { c.forEach((x) => this._s.add(x)); }, remove(...c) { c.forEach((x) => this._s.delete(x)); }, toggle(c) { this._s.has(c) ? this._s.delete(c) : this._s.add(c); }, contains(c) { return this._s.has(c); } },
        style: {},
        setAttribute() { },
        removeAttribute() { },
        focus() { },
        appendChild(child) { return child; },
        addEventListener() { },
    };
    Object.defineProperty(el, 'innerHTML', { get() { return ''; }, set() { } });
    return el;
}

const sandbox = {
    console,
    JSON,
    Math,
    Object,
    String,
    Number,
    Array,
    RegExp,
    Error,
    Uint8Array,
    parseInt,
    parseFloat,
    isNaN,
    atob: (s) => Buffer.from(s, 'base64').toString('binary'),
    btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
    setTimeout,
    navigator: {},
    document: {
        getElementById: (id) => (elements[id] || (elements[id] = makeEl(id))),
        createElement: (tag) => makeEl('created-' + tag),
        body: makeEl('body'),
    },
    lucide: { createIcons() { } },
    requestAnimationFrame: (cb) => setTimeout(cb, 0),
    URL: { createObjectURL: () => 'blob:stub', revokeObjectURL() { } },
};
sandbox.window = sandbox;
vm.createContext(sandbox);

try {
    vm.runInContext(script, sandbox);
} catch (err) {
    console.error('FEHLER: Skript-Auswertung fehlgeschlagen: ' + err.message);
    process.exit(1);
}

// --- Zugriff auf die reinen Stego-Funktionen ---
const MODE_RGB = vm.runInContext('STEGO_MODE_RGB', sandbox);
const MODE_GRAY = vm.runInContext('STEGO_MODE_GRAY', sandbox);
const HEADER_BITS = vm.runInContext('STEGO_HEADER_BITS', sandbox);
const LEGACY_BITS = vm.runInContext('LEGACY_HEADER_BITS', sandbox);

// Kleiner Harness IM Sandbox-Realm (vermeidet Realm-Mischung bei TypedArrays).
vm.runInContext(`
    function __asciiToBytes(ascii) {
        const out = new Uint8Array(ascii.length);
        for (let i = 0; i < ascii.length; i++) out[i] = ascii.charCodeAt(i) & 0xFF;
        return out;
    }
    function __embed(pixels, mode, ascii) {
        const payloadBytes = __asciiToBytes(ascii);
        const headerBits = bytesToBitArray(buildStegoHeader(mode, payloadBytes));
        const payloadBits = bytesToBitArray(payloadBytes);
        const all = new Uint8Array(headerBits.length + payloadBits.length);
        all.set(headerBits, 0);
        all.set(payloadBits, headerBits.length);
        return embedBitsIntoPixels(pixels, mode, all);
    }
    // Altformat (bis v2.8.1) nachbauen: 32-Bit-Laenge in BITS, kein Magic.
    function __embedLegacy(pixels, mode, ascii) {
        const payloadBits = bytesToBitArray(__asciiToBytes(ascii));
        const headerBits = new Uint8Array(32);
        for (let i = 0; i < 32; i++) headerBits[i] = (payloadBits.length >> (31 - i)) & 1;
        const all = new Uint8Array(32 + payloadBits.length);
        all.set(headerBits, 0);
        all.set(payloadBits, 32);
        return embedBitsIntoPixels(pixels, mode, all);
    }
    function __oldHeuristic(pixels) {
        // Exakt die frueher verwendete Rate-Logik (erste 100 Pixel).
        for (let i = 0; i < Math.min(400, pixels.length); i += 4) {
            if (Math.abs(pixels[i] - pixels[i + 1]) > 1 || Math.abs(pixels[i + 1] - pixels[i + 2]) > 1) {
                return 'RGB';
            }
        }
        return 'GRAY';
    }
`, sandbox);

// --- Test-Helfer ---
let failures = 0;
function check(condition, label, extra) {
    if (condition) {
        console.log('OK      ' + label + (extra === undefined ? '' : '  ->  ' + extra));
    } else {
        failures++;
        console.log('FEHLER  ' + label + (extra === undefined ? '' : '  ->  ' + extra));
    }
}
function assertEqual(actual, expected, label) {
    const ok = JSON.stringify(actual) === JSON.stringify(expected);
    if (ok) {
        console.log('OK      ' + label + '  ->  ' + JSON.stringify(actual));
    } else {
        failures++;
        console.log('FEHLER  ' + label + '  ->  erwartet ' + JSON.stringify(expected) + ', erhalten ' + JSON.stringify(actual));
    }
}

/** Traegerbild bauen. grayish = true erzeugt ein Bild mit r=g=b (Graustufen-Optik). */
function makeCarrier(pixelCount, grayish) {
    const data = new Uint8Array(pixelCount * 4);
    for (let i = 0; i < pixelCount; i++) {
        const v = grayish ? (30 + (i % 9)) : ((i * 37) % 256);
        data[i * 4] = v;
        data[i * 4 + 1] = grayish ? v : (255 - v);
        data[i * 4 + 2] = grayish ? v : ((v * 3) % 256);
        data[i * 4 + 3] = 255;
    }
    return data;
}

function readHeader(pixels) {
    const header = sandbox.readStegoHeader(pixels);
    return {
        found: header.found,
        mode: header.mode,
        version: header.version,
        payloadByteLength: header.payloadByteLength,
        lengthOk: header.lengthOk,
        crcOk: header.crcOk,
        payload: header.payloadBytes ? sandbox.bytesToAsciiString(header.payloadBytes) : null,
    };
}

const PAYLOAD = 'Qk9TLWJlZXAtbivkuJbnb3JkZXJ1bmctYmVpLWRlci1WZXJzY2hsdWVzc2VsdW5nLTEyMzQ1Ng==';
const CARRIER_PIXELS = 4096;   // 64x64 Pixel: reicht fuer Kopf + Nutzlast

/** Fuehrt Code im Sandbox-Kontext aus; Variablen werden vorher dort abgelegt. */
function vmCall(expr, variables) {
    if (variables) Object.assign(sandbox, variables);
    return vm.runInContext(expr, sandbox);
}

// --- 1) Round-Trip RGB auf einem GRAUEN Traegerbild (der kritische Fall) ---
console.log('--- Round-Trip RGB auf grauem Traegerbild (Regression) ---');
{
    const pixels = makeCarrier(CARRIER_PIXELS, true);
    const oldGuess = vmCall('__oldHeuristic(__p)', { __p: pixels });
    assertEqual(oldGuess, 'GRAY', 'Alte Heuristik haette dieses Bild als Graustufen gelesen (= der Fehler)');

    const embedded = vmCall('__embed(__p, __m, __s)', { __p: pixels, __m: MODE_RGB, __s: PAYLOAD });
    check(embedded === true, 'Einbetten im RGB-Modus erfolgreich');

    const header = readHeader(pixels);
    check(header.found, 'Kopf gefunden');
    assertEqual(header.mode, MODE_RGB, 'Modus wird aus der Magic gelesen (nicht geraten)');
    assertEqual(header.version, 2, 'Format-Version ist 2');
    assertEqual(header.crcOk, true, 'CRC16 stimmt');
    assertEqual(header.payload, PAYLOAD, 'Nutzlast ist bit-identisch zurueckgelesen');
}

// --- 2) Round-Trip Graustufen-Modus ---
console.log('--- Round-Trip Graustufen-Modus ---');
{
    const pixels = makeCarrier(CARRIER_PIXELS, true);
    const embedded = vmCall('__embed(__p, __m, __s)', { __p: pixels, __m: MODE_GRAY, __s: PAYLOAD });
    check(embedded === true, 'Einbetten im Graustufen-Modus erfolgreich');

    const header = readHeader(pixels);
    assertEqual(header.mode, MODE_GRAY, 'Modus Graustufen erkannt');
    assertEqual(header.crcOk, true, 'CRC16 stimmt (Graustufen)');
    assertEqual(header.payload, PAYLOAD, 'Nutzlast stimmt (Graustufen)');
}

// --- 3) Buntes Traegerbild in beiden Modi ---
console.log('--- Buntes Traegerbild (beide Modi) ---');
for (const entry of [['RGB', MODE_RGB], ['Graustufen', MODE_GRAY]]) {
    const pixels = makeCarrier(CARRIER_PIXELS, false);
    vmCall('__embed(__p, __m, __s)', { __p: pixels, __m: entry[1], __s: PAYLOAD });
    const header = readHeader(pixels);
    assertEqual(header.mode, entry[1], 'Modus ' + entry[0] + ' korrekt erkannt');
    assertEqual(header.payload, PAYLOAD, 'Nutzlast stimmt (' + entry[0] + ')');
}

// --- 4) Beschaedigung wird per CRC16 erkannt (statt "Passwort falsch") ---
console.log('--- Beschaedigung des Pixel-Betts (CRC16) ---');
{
    const pixels = makeCarrier(CARRIER_PIXELS, false);
    vmCall('__embed(__p, __m, __s)', { __p: pixels, __m: MODE_RGB, __s: PAYLOAD });

    // Ein einziges gekipptes LSB INNERHALB der Nutzlast simulieren.
    const index = HEADER_BITS + 26 * 8 + 3;
    const byteIndex = Math.floor(index / 3) * 4 + (index % 3);
    pixels[byteIndex] = pixels[byteIndex] ^ 1;

    const header = readHeader(pixels);
    check(header.found, 'Kopf weiterhin gefunden (Magic bleibt gueltig)');
    assertEqual(header.crcOk, false, 'CRC16 erkennt die Veraenderung');
    assertEqual(header.lengthOk, true, 'Laenge bleibt plausibel -> klare Diagnose "beschaedigt"');
    check(header.payload !== PAYLOAD, 'Nutzlast weicht ab (kein stiller Fehler)');
}

// --- 5) Kopf-Bits: Reihenfolge, Laenge, CRC ---
console.log('--- Kopf-Bits (Reihenfolge, Laenge, CRC) ---');
{
    const bytes = sandbox.bytesToBitArray(new Uint8Array([0x50, 0x42, 0x02, 0x00, 0x01]));
    assertEqual(
        Array.from(bytes),
        [0, 1, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0,
         0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1],
        'MSB-first Bitreihenfolge'
    );
    const back = sandbox.bytesFromBits(bytes, 0, 5);
    assertEqual(Array.from(back), [0x50, 0x42, 0x02, 0x00, 0x01], 'bytesFromBits ist die Umkehrfunktion');

    const headerBytes = sandbox.buildStegoHeader(MODE_RGB, new Uint8Array(300));
    assertEqual(headerBytes[0], 0x50, "Magic 'P'");
    assertEqual(headerBytes[1], 0x42, "Magic 'B'");
    assertEqual(headerBytes[2], 2, 'Version');
    assertEqual(headerBytes[3], MODE_RGB, 'Modus-Byte RGB');
    assertEqual(
        [headerBytes[4], headerBytes[5], headerBytes[6], headerBytes[7]],
        [0, 0, 0x01, 0x2C],
        'Laenge 300 als Big-Endian-Bytes'
    );
}

// --- 6) Kapazitaetsgrenzen ---
console.log('--- Kapazitaet ---');
{
    const pixels = makeCarrier(64, false);   // 64 Pixel
    assertEqual(sandbox.stegoBitCapacity(pixels, MODE_RGB), 64 * 3, 'Kapazitaet RGB = 3 Bit/Pixel');
    assertEqual(sandbox.stegoBitCapacity(pixels, MODE_GRAY), 64, 'Kapazitaet Graustufen = 1 Bit/Pixel');
    const tooMuch = new Uint8Array(64 * 3 + 1);
    assertEqual(sandbox.embedBitsIntoPixels(pixels, MODE_RGB, tooMuch), false, 'Ueberlauf wird gemeldet (RGB)');
}

// --- 7) Alpha wird voll deckend geschrieben ---
console.log('--- Alpha-Absicherung ---');
{
    const pixels = makeCarrier(16, false);
    for (let i = 3; i < pixels.length; i += 4) pixels[i] = 0;
    sandbox.forceOpaquePixels(pixels);
    let opaque = true;
    for (let i = 3; i < pixels.length; i += 4) { if (pixels[i] !== 255) opaque = false; }
    check(opaque, 'Alle Alpha-Werte sind 255 (keine Praemultiplikations-Verluste)');
}

// --- 8) Altformat bleibt lesbar ---
console.log('--- Altformat (bis v2.8.1) ---');
{
    const pixels = makeCarrier(CARRIER_PIXELS, false);
    vmCall('__embedLegacy(__p, __m, __s)', { __p: pixels, __m: MODE_RGB, __s: PAYLOAD });
    assertEqual(readHeader(pixels).found, false, 'Neuer Kopf wird (korrekt) nicht gefunden');
    const legacy = vmCall('readLegacyPayload(__p)', { __p: pixels });
    assertEqual(legacy ? legacy.payload : null, PAYLOAD, 'Altformat-Nutzlast wird weiterhin gelesen');
    assertEqual(legacy ? legacy.mode : null, MODE_RGB, 'Altformat-Modus (geraten) = RGB');
}

// --- 9) Bildformat-/PNG-Helfer ---
console.log('--- Bildformat und PNG-Kopf ---');
{
    const png = new Uint8Array(33);
    const signature = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A];
    for (let i = 0; i < signature.length; i++) png[i] = signature[i];
    png[11] = 13; png[12] = 0x49; png[13] = 0x48; png[14] = 0x44; png[15] = 0x52;   // "IHDR"
    png[16] = 0x00; png[17] = 0x00; png[18] = 0x0B; png[19] = 0xB8;                   // 3000
    png[20] = 0x00; png[21] = 0x00; png[22] = 0x0C; png[23] = 0x80;                   // 3200

    assertEqual(sandbox.detectImageFormat(png), 'png', 'PNG erkannt');
    const dims = sandbox.readPngDimensions(png);
    assertEqual(dims ? dims.width + 'x' + dims.height : null, '3000x3200', 'IHDR-Groesse gelesen');

    const jpeg = new Uint8Array([0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0, 0, 0, 0, 0]);
    assertEqual(sandbox.detectImageFormat(jpeg), 'jpeg', 'JPEG erkannt (wird abgelehnt)');

    const dataUrl = 'data:image/png;base64,' + Buffer.from(png).toString('base64');
    assertEqual(sandbox.verifyPngDataUrl(dataUrl, 3000, 3200).ok, true, 'Erzeugtes PNG passt zur erwarteten Groesse');
    assertEqual(sandbox.verifyPngDataUrl(dataUrl, 1500, 1600).ok, false, 'Skalierte Fassung wird erkannt');

    const errorText = sandbox.stegoLoadErrorMessage({ code: 'jpeg', detail: '' });
    check(errorText.indexOf('JPEG') >= 0, 'JPEG-Fehlertext nennt die Ursache');
}

console.log('');
if (failures === 0) {
    console.log('ALLE TESTS BESTANDEN');
} else {
    console.log(failures + ' TEST(S) FEHLGESCHLAGEN');
    process.exit(1);
}
