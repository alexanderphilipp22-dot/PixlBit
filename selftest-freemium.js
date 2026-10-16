/**
 * Selbsttest der Freemium-Logik aus assets/index.html.
 *
 * Es wird NICHT die Logik nachgebaut, sondern der echte <script>-Block der
 * index.html extrahiert und in einer VM mit DOM-/Bridge-Stubs ausgefuehrt.
 * Geprueft werden die Grenzfaelle des 50-Woerter-Limits, der Testphase,
 * der Audio-Regel und des Premium-Status.
 *
 * Aufruf:  node selftest-freemium.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const htmlPath = path.join(__dirname, 'PixlBit-Android', 'app', 'src', 'main', 'assets', 'index.html');
const html = fs.readFileSync(htmlPath, 'utf8');
const match = html.match(/JAVASCRIPT SYSTEM ENGINE -->\s*<script>([\s\S]*?)<\/script>/);
if (!match) {
    console.error('FEHLER: <script>-Block der Encoding-Engine nicht gefunden.');
    process.exit(1);
}
const script = match[1];

// --- DOM-/Bridge-Stubs: nur so viel, wie die Engine beim Laden beruehrt. ---
const elements = {};
function makeEl(id) {
    const el = {
        id,
        value: '',
        textContent: '',
        className: '',
        disabled: false,
        focused: false,
        children: [],
        style: {},
        classList: {
            _s: new Set(),
            add(...c) { c.forEach((x) => this._s.add(x)); },
            remove(...c) { c.forEach((x) => this._s.delete(x)); },
            toggle(c) { if (this._s.has(c)) this._s.delete(c); else this._s.add(c); },
            contains(c) { return this._s.has(c); }
        },
        setAttribute() { },
        removeAttribute() { },
        focus() { this.focused = true; },
        appendChild(child) { this.children.push(child); return child; }
    };
    // innerHTML = '' muss die Kinder wirklich entfernen (Matrix-Regen).
    Object.defineProperty(el, 'innerHTML', {
        get() { return ''; },
        set() { el.children.length = 0; }
    });
    return el;
}
const sandbox = {
    console,
    JSON,
    Object,
    String,
    Number,
    Math,
    RegExp,
    Error,
    URL,
    setTimeout,
    navigator: {},
    document: {
        getElementById: (id) => (elements[id] || (elements[id] = makeEl(id))),
        createElement: (tag) => makeEl('created-' + tag)
    },
    lucide: { createIcons() { } },
    requestAnimationFrame: (cb) => setTimeout(cb, 0)
};
// window ohne AndroidBridge => Engine nutzt FALLBACK_PREMIUM_STATE.
sandbox.window = sandbox;
vm.createContext(sandbox);

try {
    vm.runInContext(script, sandbox);
} catch (err) {
    console.error('FEHLER: Skript-Auswertung fehlgeschlagen: ' + err.message);
    process.exit(1);
}

let failures = 0;
function assertEqual(actual, expected, label) {
    const ok = JSON.stringify(actual) === JSON.stringify(expected);
    if (ok) {
        console.log('OK      ' + label + '  ->  ' + JSON.stringify(actual));
    } else {
        failures++;
        console.log('FEHLER  ' + label + '  ->  erwartet ' + JSON.stringify(expected) + ', erhalten ' + JSON.stringify(actual));
    }
}

const setState = (state) =>
    vm.runInContext('premiumState = Object.assign({}, FALLBACK_PREMIUM_STATE, ' + JSON.stringify(state) + ');', sandbox);
const gate = (text) => vm.runInContext('evaluateFreemiumGate(' + JSON.stringify(text) + ')', sandbox);
const countWords = (text) => vm.runInContext('countWords(' + JSON.stringify(text) + ')', sandbox);
const words = (n) => Array.from({ length: n }, () => 'wort').join(' ');

console.log('--- Wortzaehlung ---');
assertEqual(countWords(''), 0, 'countWords("") = 0');
assertEqual(countWords('   '), 0, 'countWords(nur Whitespace) = 0');
assertEqual(countWords('Hallo Welt'), 2, 'countWords("Hallo Welt") = 2');
assertEqual(countWords('Hallo   Welt\n Zeile\t2'), 4, 'countWords(Mehrfach-Whitespace) = 4');
assertEqual(countWords(words(50)), 50, 'countWords(50 Woerter) = 50');
assertEqual(countWords(words(51)), 51, 'countWords(51 Woerter) = 51');

console.log('--- Testphase aktiv (Tag 1-3): uneingeschraenkt ---');
setState({ premium: false, trialActive: true, trialExpired: false, daysRemaining: 3, maxFreeWords: 50 });
assertEqual(gate(words(50)), null, 'Testphase: 50 Woerter erlaubt');
assertEqual(gate(words(51)), null, 'Testphase: 51 Woerter erlaubt');
assertEqual(gate(words(500)), null, 'Testphase: 500 Woerter erlaubt');
assertEqual(gate('data:audio/webm;base64,AAAA'), null, 'Testphase: Audio erlaubt');

console.log('--- Testphase beendet, kein Premium: kostenlose Basisversion ---');
setState({ premium: false, trialActive: false, trialExpired: true, daysRemaining: 0, maxFreeWords: 50 });
assertEqual(gate(words(1)), null, 'Basis: 1 Wort erlaubt');
assertEqual(gate(words(49)), null, 'Basis: 49 Woerter erlaubt');
assertEqual(gate(words(50)), null, 'Basis: genau 50 Woerter erlaubt (Grenze)');
assertEqual(gate(words(51)), 'word-limit', 'Basis: 51 Woerter blockiert (Grenze)');
assertEqual(gate(words(200)), 'word-limit', 'Basis: 200 Woerter blockiert');
assertEqual(gate('data:audio/webm;base64,AAAA'), 'audio-premium', 'Basis: Audio blockiert');

console.log('--- Premium aktiv: keine Limits ---');
setState({ premium: true, trialActive: false, trialExpired: false, daysRemaining: 0, maxFreeWords: 50 });
assertEqual(gate(words(1)), null, 'Premium: 1 Wort erlaubt');
assertEqual(gate(words(1000)), null, 'Premium: 1000 Woerter erlaubt');
assertEqual(gate('data:audio/mp4;base64,AAAA'), null, 'Premium: Audio erlaubt');

console.log('--- Wortzaehler-Anzeige (echtes updateWordCounter) ---');
const counter = elements['word-count-indicator'];
const field = elements['secret-message'];
setState({ premium: false, trialActive: false, trialExpired: true, maxFreeWords: 50 });
field.value = words(51);
vm.runInContext('updateWordCounter()', sandbox);
assertEqual(counter.textContent, '51 / 50 Wörter – Limit überschritten', 'Anzeige ueberschrittenes Limit');
assertEqual(counter.className.indexOf('text-red-400') >= 0, true, 'Anzeige rot markiert');
field.value = words(12);
vm.runInContext('updateWordCounter()', sandbox);
assertEqual(counter.textContent, '12 / 50 Wörter', 'Anzeige unterhalb des Limits');
assertEqual(counter.className.indexOf('text-red-400') === -1, true, 'Anzeige nicht rot');
setState({ premium: false, trialActive: true, trialExpired: false, maxFreeWords: 50 });
vm.runInContext('updateWordCounter()', sandbox);
assertEqual(counter.textContent, '12 Wörter (Testphase)', 'Anzeige waehrend Testphase');
setState({ premium: true, trialActive: false, trialExpired: false, maxFreeWords: 50 });
vm.runInContext('updateWordCounter()', sandbox);
assertEqual(counter.textContent, '12 Wörter (Premium)', 'Anzeige mit Premium');

console.log('--- Sicherheits-Invarianten (Schutz der Kernlogik) ---');
// Der Anhang-Button (Büroklammer) wurde entfernt - es darf kein toter Picker-Code
// und kein verwaister Event-Handler übrig bleiben.
assertEqual(html.indexOf('handleAttachFile') === -1 && html.indexOf('attach-file-input') === -1 && html.indexOf('data-lucide="paperclip"') === -1, true, 'Anhang-Button und Picker-Code entfernt');
assertEqual(script.indexOf('attachAudioPayload(e.target.result, fileName, blob.size)') >= 0, true, 'Aufnahme laeuft weiterhin als Nutzlast in den Verschluesselungs-Flow');
assertEqual(html.indexOf("rawMessage.indexOf('data:image/') === 0") >= 0, true, 'Bild-Payload wird abgelehnt (keine Bild-in-Bild)');
assertEqual(html.indexOf('window.AndroidBridge.aesEncrypt(rawMessage, passphrase)') >= 0, true, 'AES-256 laeuft nativ ueber die Bridge');

console.log('--- Matrix-Animation (Digital Rain) ---');
assertEqual(/async function executeEncoding/.test(script), true, 'executeEncoding ist asynchron (blockiert die Oberflaeche nicht)');
assertEqual(script.indexOf('await nextPaint()') >= 0, true, 'Ein Frame wird vor der Krypto-Phase abgewartet');
assertEqual(script.indexOf('await ensureMatrixMinDuration()') >= 0, true, 'Mindestlaufzeit der Animation abgewartet');
const matrixSection = script.substring(script.indexOf('const MATRIX_GLYPHS'), script.indexOf('async function executeEncoding'));
assertEqual(matrixSection.indexOf('setInterval') === -1, true, 'Matrix-Code laeuft ohne Timer-Schleife (speicherschonend)');
assertEqual(html.indexOf('@keyframes matrix-fall') >= 0, true, 'CSS-Keyframes fuer den Regen vorhanden');
assertEqual(html.indexOf('prefers-reduced-motion') >= 0, true, 'reduzierte Bewegung wird respektiert');

const button = elements['encode-button'] || (elements['encode-button'] = makeEl('encode-button'));
const rain = elements['matrix-rain'] || (elements['matrix-rain'] = makeEl('matrix-rain'));
const buttonLabel = elements['encode-button-label'] || (elements['encode-button-label'] = makeEl('encode-button-label'));

console.log('--- Mikrofon-Aufnahme (Race-Condition & Aufraeumen) ---');
assertEqual(script.indexOf('await requestMicrophonePermissionNative()') >= 0, true, 'Berechtigung wird VOR getUserMedia eingeholt');
assertEqual(script.indexOf('permissionGranted === false') >= 0, true, 'Ablehnung wird vor getUserMedia erkannt');
assertEqual(script.indexOf('await openMicrophoneStream()') >= 0, true, 'Stream wird mit Retry geholt');
assertEqual(script.indexOf('showNotification("Mikrofon blockiert"') === -1, true, 'Generische Fehlermeldung entfernt');
assertEqual(script.indexOf('function onMicrophonePermissionResult(granted)') >= 0, true, 'Nativer Ergebnis-Callback vorhanden');
const finalizeSection = script.substring(script.indexOf('function finalizeRecording()'), script.indexOf('function resetRecordingUI()'));
assertEqual(finalizeSection.indexOf('finally {') >= 0, true, 'finalizeRecording nutzt try/finally');
assertEqual(finalizeSection.indexOf('releaseMicrophoneStream();') > finalizeSection.indexOf('finally {'), true, 'Mikrofon-Freigabe steht im finally-Block');
assertEqual(script.indexOf("mediaRecorder.onstop = null") >= 0, true, 'Wipe haengt Recorder-Handler vor dem Stop ab');

runMatrixTests().then(runMicrophoneTests).then(finish);

async function runMicrophoneTests() {
    let stopped = 0;
    sandbox.__fakeStream = { getTracks: () => [{ stop: () => { stopped++; } }] };
    vm.runInContext('recordingStream = __fakeStream', sandbox);
    vm.runInContext('releaseMicrophoneStream()', sandbox);
    assertEqual(stopped, 1, 'Mikrofon-Track wird gestoppt');
    assertEqual(vm.runInContext('recordingStream', sandbox), null, 'Stream wird verworfen');

    let bridgeCalls = 0;
    sandbox.window.AndroidBridge = {
        hasMicrophonePermission: () => false,
        requestMicrophonePermission: () => { bridgeCalls++; return true; }
    };
    const pending = vm.runInContext('requestMicrophonePermissionNative()', sandbox);
    assertEqual(bridgeCalls, 1, 'Native Vorabanfrage wird ausgeloest');
    let resolved = 'unresolved';
    pending.then((value) => { resolved = value; });
    await new Promise((r) => setTimeout(r, 30));
    assertEqual(resolved, 'unresolved', 'Promise wartet auf das native Ergebnis');
    vm.runInContext('onMicrophonePermissionResult(true)', sandbox);
    await new Promise((r) => setTimeout(r, 30));
    assertEqual(resolved, true, 'Grant wird an JavaScript gemeldet');

    sandbox.window.AndroidBridge = {
        hasMicrophonePermission: () => true,
        requestMicrophonePermission: () => { throw new Error('darf nicht aufgerufen werden'); }
    };
    assertEqual(await vm.runInContext('requestMicrophonePermissionNative()', sandbox), true, 'Bei erteilter Berechtigung keine erneute Anfrage');

    delete sandbox.window.AndroidBridge;
    assertEqual(await vm.runInContext('requestMicrophonePermissionNative()', sandbox), true, 'Ohne Bridge fragt getUserMedia selbst');

    assertEqual(vm.runInContext('microphoneErrorMessage({ name: "NotAllowedError" })', sandbox).indexOf('verweigert') >= 0, true, 'NotAllowedError wird erklaert');
    const notReadable = vm.runInContext('microphoneErrorMessage({ name: "NotReadableError" })', sandbox);
    assertEqual(notReadable.indexOf('NotReadableError') >= 0, true, 'NotReadableError: technischer Name steht in der Meldung');
    assertEqual(notReadable.indexOf('Technischer Fehler') >= 0, true, 'Meldung weist den technischen Fehler explizit aus');
    assertEqual(vm.runInContext('microphoneErrorMessage({ name: "NotFoundError" })', sandbox).indexOf('kein Mikrofon') >= 0, true, 'NotFoundError wird erklaert');

    // Keine pauschalen Vermutungen mehr: die frueher fest verdrahtete Aussage
    // "wird von einer anderen App belegt" darf nicht mehr auftauchen.
    assertEqual(html.indexOf('Das Mikrofon wird gerade von einer anderen App belegt') === -1, true, 'Irrefuehrende Pauschalaussage entfernt');
    assertEqual(script.indexOf('function microphoneErrorDetail(') >= 0, true, 'Technischer Fehler wird aus err.name/err.message gebildet');
    const withMessage = vm.runInContext('microphoneErrorMessage({ name: "NotReadableError", message: "Could not start audio source", code: 7 })', sandbox);
    assertEqual(withMessage.indexOf('Could not start audio source') >= 0, true, 'err.message wird im Klartext ausgegeben');
    assertEqual(withMessage.indexOf('Code 7') >= 0, true, 'err.code wird ausgegeben');

    sandbox.window.AndroidBridge = {
        microphoneDiagnostics: () => '{"recordAudioGranted":false,"sdkInt":33,"webViewVersion":"1.2.3"}'
    };
    const withDiagnostics = vm.runInContext('microphoneErrorMessage({ name: "NotReadableError" })', sandbox);
    assertEqual(withDiagnostics.indexOf('RECORD_AUDIO NICHT erteilt') >= 0, true, 'Nicht erteilte Laufzeitberechtigung wird ausgewiesen');
    assertEqual(withDiagnostics.indexOf('WebView 1.2.3') >= 0, true, 'WebView-Version wird ergaenzt');
    delete sandbox.window.AndroidBridge;
}

async function runMatrixTests() {
    vm.runInContext('startMatrixEffect()', sandbox);
    assertEqual(button.classList.contains('matrix-active'), true, 'Button erhaelt matrix-active');
    assertEqual(button.disabled, true, 'Button waehrend der Verarbeitung gesperrt');
    assertEqual(buttonLabel.textContent, 'AES-256 läuft…', 'Button zeigt Arbeitszustand');
    assertEqual(rain.children.length > 0, true, 'Regen-Spalten wurden erzeugt');
    assertEqual(rain.children.every((col) => col.className === 'matrix-column'), true, 'Alle Spalten nutzen .matrix-column');
    assertEqual(rain.children.every((col) => col.textContent.length > 0), true, 'Spalten enthalten Zeichen');

    await vm.runInContext('nextPaint()', sandbox);
    assertEqual(true, true, 'nextPaint() loest auf (kein Deadlock)');

    vm.runInContext('finishMatrixEffect(true)', sandbox);
    assertEqual(buttonLabel.textContent, 'Verschlüsselt & gesichert', 'Button zeigt Erfolgszustand');
    assertEqual(button.classList.contains('matrix-done'), true, 'Button erhaelt matrix-done');
    assertEqual(rain.children.length, 0, 'Regen-Spalten sofort aus dem DOM entfernt');
    assertEqual(button.disabled, false, 'Button wieder klickbar');

    await new Promise((r) => setTimeout(r, 1300));
    assertEqual(button.classList.contains('matrix-active') || button.classList.contains('matrix-done'), false, 'Button faellt in den Normalzustand zurueck');
    assertEqual(buttonLabel.textContent, 'In Pixel einbetten & Sichern', 'Button zeigt wieder das urspruengliche Label');

    vm.runInContext('startMatrixEffect()', sandbox);
    vm.runInContext('finishMatrixEffect(false)', sandbox);
    assertEqual(button.classList.contains('matrix-active'), false, 'Fehlerfall: sofortiger Rueckbau');
    assertEqual(buttonLabel.textContent, 'In Pixel einbetten & Sichern', 'Fehlerfall: Normalzustand');
}

function finish() {
    console.log('');
    if (failures === 0) {
        console.log('ALLE TESTS BESTANDEN');
    } else {
        console.log(failures + ' TEST(S) FEHLGESCHLAGEN');
        process.exit(1);
    }
}
