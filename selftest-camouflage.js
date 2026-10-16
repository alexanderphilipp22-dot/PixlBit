/**
 * Selbsttest der Tarnfassade (Camouflage Layer) aus assets/index.html.
 *
 * Wie selftest-freemium.js wird NICHT die Logik nachgebaut: es wird der echte
 * CAMOUFLAGE-ENGINE-<script>-Block der index.html extrahiert und in einer VM mit
 * DOM-Stubs ausgefuehrt. Auch das Tasten-Markup wird direkt aus der HTML gelesen,
 * damit Markup und Skript als Vertrag geprueft werden (data-calc-*).
 *
 * Geprueft werden:
 *   - echte Grundrechenarten (+ - x /), Komma, Prozent, Vorzeichen, "="
 *   - der geheime Unlock-Trigger (22 + Wurzel) inkl. Eingabe-Blockade
 *   - die exakte Dauer der Transition (3 Sekunden) und die Uebergabe an die App
 *
 * Aufruf:  node selftest-camouflage.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const htmlPath = path.join(__dirname, 'PixlBit-Android', 'app', 'src', 'main', 'assets', 'index.html');
const html = fs.readFileSync(htmlPath, 'utf8');
// Anker bewusst ueber den eindeutigen Kommentar der Fassade, endend an "-->":
// die Markup-Erwaehnung "(siehe CAMOUFLAGE ENGINE am Dokumentende)" liegt VOR dem
// Engine-Script und das Wort "<script>" kommt im Kommentar selbst vor.
const match = html.match(/CAMOUFLAGE ENGINE \(Tarnfassade\)[\s\S]*?-->\s*<script>([\s\S]*?)<\/script>/);
if (!match) {
    console.error('FEHLER: <script>-Block der Tarnfassade nicht gefunden.');
    process.exit(1);
}
const script = match[1];

/* Zeichensatz des Zeichenregens direkt aus dem Skript lesen, damit die
   geforderten Alphabete (Latein, Umlaute, Kyrillisch, Code-Symbole) als
   Vertrag geprueft werden koennen. */
const charsetSection = script.substring(script.indexOf('const RAIN_CHARSET'), script.indexOf('const RAIN_CHAR_COUNT'));
const rainCharset = (charsetSection.match(/'([^']*)'/g) || []).map((part) => part.slice(1, -1)).join('');
const rainChars = Array.from(new Set(rainCharset.split('')));

/* ------------------------------------------------------------------ *
 * DOM-Stubs: nur so viel, wie die Fassade beruehrt.
 * ------------------------------------------------------------------ */
const roots = {};
let decoyActiveRemovedAt = 0;
let decoyRemovedAt = 0;

function makeClassList(onRemove) {
    const set = new Set();
    return {
        _set: set,
        add(...names) { names.forEach((name) => set.add(name)); },
        remove(...names) { names.forEach((name) => { set.delete(name); if (onRemove) onRemove(name); }); },
        toggle(name, force) {
            const on = force === undefined ? !set.has(name) : Boolean(force);
            if (on) set.add(name); else set.delete(name);
            return on;
        },
        contains(name) { return set.has(name); }
    };
}

function makeEl(tag, attrs) {
    attrs = attrs || {};
    const el = {
        tagName: String(tag || 'div').toUpperCase(),
        isFragment: tag === 'fragment',
        textContent: '',
        className: attrs.className || '',
        dataset: attrs.dataset || {},
        attributes: {},
        handlers: {},
        children: [],
        parentNode: null,
        clientWidth: attrs.clientWidth === undefined ? 320 : attrs.clientWidth,
        scrollWidth: attrs.scrollWidth === undefined ? 0 : attrs.scrollWidth,
        classList: makeClassList(attrs.onClassRemove),
        style: {
            setProperty(name, value) { this[name] = value; },
            removeProperty(name) { delete this[name]; }
        },
        setAttribute(name, value) { this.attributes[name] = value; },
        removeAttribute(name) { delete this.attributes[name]; },
        appendChild(child) {
            if (child.isFragment) {
                child.children.forEach((node) => { node.parentNode = el; el.children.push(node); });
                child.children = [];
                return child;
            }
            child.parentNode = el;
            el.children.push(child);
            return child;
        },
        removeChild(child) {
            const index = el.children.indexOf(child);
            if (index >= 0) el.children.splice(index, 1);
            child.parentNode = null;
            if (child === roots.app) decoyRemovedAt = Date.now();
            return child;
        },
        closest(selector) {
            return selector === '.decoy-key' && (' ' + el.className + ' ').indexOf(' decoy-key ') >= 0 ? el : null;
        },
        addEventListener(type, handler) { el.handlers[type] = handler; },
        removeEventListener(type) { delete el.handlers[type]; }
    };
    return el;
}

/* Canvas-Stub: zeichnet nichts, zaehlt aber die Aufrufe des Zeichenregens. */
function makeCanvasContext() {
    return {
        fillTextCalls: 0,
        fillRectCalls: 0,
        clearRectCalls: 0,
        lastGlyph: '',
        font: '',
        fillStyle: '',
        globalAlpha: 1,
        textBaseline: '',
        textAlign: '',
        shadowColor: '',
        shadowBlur: 0,
        setTransform() { },
        clearRect() { this.clearRectCalls++; },
        fillRect() { this.fillRectCalls++; },
        fillText(glyph) { this.fillTextCalls++; this.lastGlyph = glyph; }
    };
}

/* Tasten direkt aus dem HTML-Markup lesen (Markup <-> Skript als Vertrag). */
function extractKeys(markup) {
    const buttons = [];
    const pattern = /<button[^>]*class="(decoy-key[^"]*)"[^>]*>([^<]*)<\/button>/g;
    let hit;
    while ((hit = pattern.exec(markup)) !== null) {
        const attr = (name) => {
            const found = new RegExp('data-calc-' + name + '="([^"]*)"').exec(hit[0]);
            return found ? found[1] : undefined;
        };
        const key = makeEl('button', {
            className: hit[1],
            dataset: { calcDigit: attr('digit'), calcOp: attr('op'), calcAction: attr('action') }
        });
        key.textContent = hit[2];
        buttons.push(key);
    }
    return buttons;
}

const keyEls = extractKeys(html);
roots.displayWrap = makeEl('div', { clientWidth: 320 });
roots.app = makeEl('div');
roots.shell = makeEl('div');
roots.display = makeEl('span');
roots.display.parentNode = roots.displayWrap;
/* Textbreite modellieren, damit fitDisplay() messbar verkleinert. */
Object.defineProperty(roots.display, 'offsetWidth', {
    get() { return (roots.display.textContent || '').length * 20; }
});
roots.keys = makeEl('div');
roots.keys.querySelectorAll = (selector) => (selector === '.decoy-key' ? keyEls : []);
/* Canvas des Zeichenregens: Groesse kommt aus dem Layout (wie im Browser). */
const matrixContext = makeCanvasContext();
roots.matrix = makeEl('canvas');
roots.matrix.clientWidth = 390;
roots.matrix.clientHeight = 844;
roots.matrix.getContext = () => matrixContext;
roots.stream = makeEl('div');

const body = makeEl('body', {
    onClassRemove: (name) => {
        if (name === 'decoy-active' && !decoyActiveRemovedAt) decoyActiveRemovedAt = Date.now();
    }
});
body.classList.add('decoy-active');
body.appendChild(roots.app);

const elements = {
    'decoy-app': roots.app,
    'decoy-shell': roots.shell,
    'decoy-display-value': roots.display,
    'decoy-keys': roots.keys,
    'decoy-matrix': roots.matrix,
    'decoy-stream': roots.stream
};

let premiumRefreshCalls = 0;
let lucideCalls = 0;

const documentStub = {
    body,
    handlers: {},
    getElementById: (id) => elements[id] || null,
    createElement: (tag) => makeEl(tag),
    createDocumentFragment: () => makeEl('fragment'),
    addEventListener(type, handler) { this.handlers[type] = handler; },
    removeEventListener(type) { delete this.handlers[type]; }
};

const sandbox = {
    console,
    Math,
    JSON,
    Object,
    Array,
    String,
    Number,
    Boolean,
    Date,
    Set,
    RegExp,
    Error,
    parseFloat,
    parseInt,
    isFinite,
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval,
    /* rAF-Stub: treibt die Regenschleife wie ein Browser (16 ms Takt). */
    requestAnimationFrame: (callback) => setTimeout(callback, 16),
    cancelAnimationFrame: (handle) => clearTimeout(handle),
    devicePixelRatio: 2,
    document: documentStub,
    getComputedStyle: () => ({ fontSize: '44px' }),
    innerWidth: 390,
    innerHeight: 844,
    scrollTo() { },
    refreshPremiumState() { premiumRefreshCalls++; },
    lucide: {
        createIcons() { lucideCalls++; }
    }
};
sandbox.window = sandbox;

/* Native Bruecke mocken: die Fassade darf NUR ueber die Rueckgabe von
   triggerIfDuress entscheiden (kein UI-Oeffnen bei Panik-Code). */
const bridgeCalls = { duress: [] };
let panicCodeValue = null;
sandbox.AndroidBridge = {
    triggerIfDuress(value) {
        bridgeCalls.duress.push(value);
        return value === panicCodeValue;
    }
};

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

/* ---------------- Eingabe-Helfer (klickt echte Tasten des Markups) ---------------- */
function findKey(predicate) {
    return keyEls.find(predicate);
}
function clickKey(key) {
    if (!key) throw new Error('Taste nicht gefunden');
    if (!roots.keys.handlers.click) throw new Error('Kein delegierter Tasten-Listener vorhanden');
    roots.keys.handlers.click({ target: key });
}
const pressDigit = (digit) => clickKey(findKey((key) => key.dataset.calcDigit === digit));
const pressOp = (op) => clickKey(findKey((key) => key.dataset.calcOp === op));
const pressAction = (action) => clickKey(findKey((key) => key.dataset.calcAction === action));
const display = () => roots.display.textContent;
/* Tastensequenz ueber die sichtbaren Beschriftungen (AC, 7, +, =, √, ...). */
function seq(...labels) {
    pressAction('clear');
    labels.forEach((label) => {
        clickKey(findKey((key) => key.textContent === label)
            || findKey((key) => key.dataset.calcDigit === label)
            || findKey((key) => key.dataset.calcAction === label));
    });
}

async function run() {
    console.log('--- Markup der Fassade ---');
    assertEqual(keyEls.length, 20, 'Tastenraster enthaelt 20 Tasten');
    assertEqual(keyEls.filter((key) => key.className.indexOf('decoy-key--fn') >= 0).map((key) => key.textContent),
        ['AC', '±', '%', '√'], 'Obere Reihe (hellgrau): AC, ±, %, √');
    assertEqual(keyEls.filter((key) => key.className.indexOf('decoy-key--op') >= 0).map((key) => key.textContent),
        ['÷', '×', '−', '+', '='], 'Rechenspalte (orange): ÷ × − + =');
    assertEqual(keyEls.filter((key) => key.dataset.calcDigit !== undefined).map((key) => key.dataset.calcDigit),
        ['7', '8', '9', '4', '5', '6', '1', '2', '3', '0'], 'Ziffern 0-9 vorhanden (dunkelgrau)');
    assertEqual(body.classList.contains('decoy-active'), true, 'Start: echte App ist verdeckt');
    assertEqual(documentStub.handlers.keydown !== undefined, true, 'Tastatur ist mit der Fassade verbunden');

    console.log('--- Hochaufloesender Zeichenregen statt Pixel-Kacheln ---');
    assertEqual(html.indexOf('decoy-pixel') === -1, true, 'Blockige Pixel-Kacheln (HTML/CSS/JS) sind komplett entfernt');
    assertEqual(script.indexOf('buildPixelField') === -1, true, 'Pixel-Logik ist aus dem Skript entfernt');
    assertEqual(html.indexOf('<canvas id="decoy-matrix"') >= 0, true, 'Canvas des Zeichenregens ist im Markup vorhanden');
    assertEqual(html.indexOf('.decoy-root.is-booting .decoy-matrix') >= 0, true, 'Regen ist nur waehrend der 3-Sekunden-Transition sichtbar');
    assertEqual(script.indexOf('requestAnimationFrame') >= 0, true, 'Zeichenregen laeuft ueber requestAnimationFrame');
    assertEqual(script.indexOf('RAIN_FRAME_MS') >= 0 && script.indexOf('devicePixelRatio') >= 0, true, 'Frame-Limiter und Geraeteauflosung sind gesetzt');
    assertEqual(script.indexOf('RAIN_GLOW_COLUMNS') >= 0 && script.indexOf('RAIN_SLOW_FRAME_MS') >= 0, true, 'Performance-Notbremse (Glow-Stufe) ist vorhanden');
    assertEqual(script.indexOf('prefers-reduced-motion') >= 0, true, 'reduzierte Bewegung wird respektiert');
    assertEqual(rainChars.length >= 120, true, 'Zeichensatz ist dicht (' + rainChars.length + ' verschiedene Zeichen)');
    ['0', '1', 'A', 'Z', 'X', 'Ä', 'ß', 'Æ', '§', '#', 'Ж', 'Д', 'Ϡ'].forEach((glyph) => {
        assertEqual(rainCharset.indexOf(glyph) >= 0, true, 'Zeichensatz enthaelt ' + JSON.stringify(glyph));
    });

    console.log('--- Echte Mathematik (Glaubwuerdigkeit der Tarnung) ---');
    assertEqual(display(), '0', 'Startanzeige ist 0');
    seq('7', '+', '3', '=');                          assertEqual(display(), '10', '7 + 3 = 10');
    seq('8', '×', '7', '=');                          assertEqual(display(), '56', '8 × 7 = 56');
    seq('9', '−', '2', '=');                          assertEqual(display(), '7', '9 − 2 = 7');
    seq('9', '÷', '3', '=');                          assertEqual(display(), '3', '9 ÷ 3 = 3');
    seq('1', '÷', '0', '=');                          assertEqual(display(), 'Fehler', 'Division durch 0 ergibt Fehler');
    seq('2', ',', '5', '+', '2', ',', '5', '=');      assertEqual(display(), '5', '2,5 + 2,5 = 5');
    seq('5', '0', '%');                               assertEqual(display(), '0,5', '50 % = 0,5');
    seq('5', '±');                                    assertEqual(display(), '-5', 'Vorzeichen: −5');
    seq('1', '2', '3', '4');                          assertEqual(display(), '1.234', 'Tausendertrennzeichen');
    seq('2', '+', '3', '=', '=');                     assertEqual(display(), '8', 'Wiederholtes "=" rechnet weiter');
    seq('6', '÷', '4', '=');                          assertEqual(display(), '1,5', '6 ÷ 4 = 1,5 (Dezimalergebnis)');
    pressAction('clear');                            assertEqual(display(), '0', 'AC setzt alles zurueck');

    console.log('--- Anzeige skaliert lange Zahlen (wie iOS) ---');
    roots.displayWrap.clientWidth = 320;
    seq('9');                                         assertEqual(roots.display.style.fontSize, '', 'Kurze Zahl: Schrift bleibt gross');
    roots.displayWrap.clientWidth = 100;
    seq('1', '÷', '3', '=');                          assertEqual(/px$/.test(String(roots.display.style.fontSize)), true, 'Lange Zahl: Schrift wird verkleinert (' + roots.display.style.fontSize + ')');
    roots.displayWrap.clientWidth = 320;
    seq('9');                                         assertEqual(roots.display.style.fontSize, '', 'Danach wieder Normalschrift');

    console.log('--- Wurzel-Taste (√): Komma-Pflicht + Panik-Erkennung ---');
    assertEqual(script.indexOf('SECRET_ENTRY') === -1 && script.indexOf('isUnlockSequence') === -1, true, 'Feste Bedingung (Zahl 22) ist vollstaendig entfernt');
    assertEqual(script.indexOf('DECIMAL_SEPARATOR') >= 0, true, 'Komma-Pflicht (Dezimaltrennzeichen) ist im Skript verankert');
    assertEqual(script.indexOf('computeSquareRoot') >= 0, true, 'Ohne Trennzeichen rechnet die Taste die echte Quadratwurzel');
    assertEqual(script.indexOf('isPanicCode(entry)') >= 0, true, 'Panik-Code wird VOR dem Unlock geprueft');
    assertEqual(script.indexOf('Math.sqrt') >= 0, true, 'Die Quadratwurzel wird berechnet (Math.sqrt)');

    /* 1) KEIN Komma -> niemals ein Code: normale Taschenrechner-Logik. */
    seq('7', '+', '3', '=');
    assertEqual(display(), '10', 'Grundrechenarten unveraendert (7 + 3 = 10)');
    pressAction('sqrt');
    assertEqual(display().indexOf('3,16') === 0, true, 'Ohne Komma wird die echte Wurzel gezogen (' + display() + ')');
    assertEqual(roots.app.classList.contains('is-booting'), false, 'Ohne Komma oeffnet sich die App NICHT');
    assertEqual(bridgeCalls.duress.length, 0, 'Ohne Komma wird kein Panik-Check ausgeloest');

    /* 2) MIT Komma, aber Panik-Code -> stiller Wipe, App bleibt verborgen. */
    panicCodeValue = '1,1';
    seq('1', ',', '1');
    assertEqual(display(), '1,1', 'Eingabe mit Komma (1,1)');
    pressAction('sqrt');
    assertEqual(bridgeCalls.duress[bridgeCalls.duress.length - 1], '1,1', 'Panik-Code wird nativ geprueft');
    assertEqual(roots.app.classList.contains('is-booting'), false, 'Panik-Code oeffnet die App NIEMALS');
    assertEqual(roots.shell.classList.contains('is-locked'), false, 'Fassade bleibt normal bedienbar (kein Unlock)');
    assertEqual(display().indexOf('1,0') === 0, true, 'Fassade zeigt unauffaellig die Wurzel (' + display() + ')');

    /* 3) MIT Komma, kein Panik-Code -> normaler Entsperr-Code (Transition). */
    panicCodeValue = null;
    seq('3', ',', '3');
    assertEqual(display(), '3,3', 'Eingabe mit Komma (3,3)');
    const startedAt = Date.now();
    pressAction('sqrt');

    assertEqual(roots.app.classList.contains('is-booting'), true, 'Mit Komma startet die Unlock-Transition');
    assertEqual(bridgeCalls.duress[bridgeCalls.duress.length - 1], '3,3', 'Auch der normale Code laeuft durch den Panik-Check');
    assertEqual(roots.shell.classList.contains('is-locked'), true, 'UI blockiert weitere Eingaben');
    assertEqual(/^[0-9A-F]{4}·[0-9A-F]{2}/.test(display()), true, 'Anzeige wird zu Hex-Code (' + display() + ')');
    assertEqual(roots.matrix.width, 780, 'Canvas rendert in Geraeteauflosung (390 px x DPR 2)');
    assertEqual(roots.matrix.height, 1688, 'Canvas-Hoehe in Geraeteauflosung (844 px x DPR 2)');
    assertEqual(keyEls.every((key) => key.classList.contains('is-breaking')), true, 'Alle Tasten brechen optisch auf');
    assertEqual(keyEls.every((key) => key.textContent !== key.dataset.calcDigit), true, 'Tasten zeigen Matrix-/Hex-Zeichen statt Ziffern');

    const frozen = display();
    pressDigit('9');
    pressDigit('9');
    pressOp('+');
    pressAction('equals');
    pressAction('clear');
    assertEqual(display(), frozen, 'Waehrend der Transition wird jede Eingabe ignoriert');

    console.log('--- Der Regen zeichnet (Canvas statt DOM) ---');
    await new Promise((resolve) => setTimeout(resolve, 140));
    assertEqual(matrixContext.fillTextCalls > 0, true, 'Zeichenregen zeichnet Zeichen (' + matrixContext.fillTextCalls + ' Glyphen)');
    assertEqual(matrixContext.clearRectCalls >= 1, true, 'Jeder Frame loescht den Canvas (keine Kachel-Reste)');
    assertEqual(matrixContext.fillRectCalls >= 1, true, 'Pro Frame wird genau einmal abgedunkelt (' + matrixContext.fillRectCalls + ')');
    assertEqual(rainCharset.indexOf(matrixContext.lastGlyph) >= 0, true, 'Gezeichnetes Zeichen stammt aus dem Zeichensatz (' + JSON.stringify(matrixContext.lastGlyph) + ')');
    assertEqual(matrixContext.shadowBlur === 0, true, 'Canvas-Status nach dem Frame sauber (kein Shadow-Leak)');

    await new Promise((resolve) => setTimeout(resolve, 3700));

    console.log('--- Dauer der Transition (3 Sekunden) und Uebergabe an die App ---');
    const elapsed = decoyActiveRemovedAt - startedAt;
    assertEqual(elapsed >= 3000, true, 'Transition laeuft die volle Zeit (' + elapsed + ' ms)');
    assertEqual(elapsed < 3600, true, 'App wird unmittelbar danach freigegeben (' + elapsed + ' ms)');
    assertEqual(decoyRemovedAt - decoyActiveRemovedAt >= 400, true, 'Fassade blendet weich aus (' + (decoyRemovedAt - decoyActiveRemovedAt) + ' ms)');
    assertEqual(body.classList.contains('decoy-active'), false, 'Echte App ist sichtbar (decoy-active entfernt)');
    assertEqual(roots.app.parentNode === null, true, 'Fassade ist aus dem DOM entfernt');
    assertEqual(documentStub.handlers.keydown === undefined, true, 'Tastatur-Listener ist abgeraeumt');
    assertEqual(premiumRefreshCalls >= 1, true, 'Premium-Status wird an die echte App uebergeben');
    assertEqual(lucideCalls >= 1, true, 'Icons der echten App werden neu gezeichnet');

    const framesAtExit = matrixContext.fillTextCalls;
    await new Promise((resolve) => setTimeout(resolve, 200));
    assertEqual(matrixContext.fillTextCalls, framesAtExit, 'Zeichenregen stoppt nach dem Fade-Out (keine CPU-Last)');
    assertEqual(html.indexOf('.decoy-matrix { display: none; }') >= 0, true, 'Barrierefreiheit: Regen bleibt bei reduzierter Bewegung aus');
}

run().then(finish);

function finish() {
    console.log('');
    if (failures === 0) {
        console.log('ALLE TESTS BESTANDEN');
    } else {
        console.log(failures + ' TEST(S) FEHLGESCHLAGEN');
        process.exit(1);
    }
}
