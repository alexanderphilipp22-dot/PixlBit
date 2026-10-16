/**
 * Selbsttest der Mehrsprachigkeit (6 Sprachen) aus assets/index.html.
 *
 * Wie selftest-freemium.js wird NICHT die Logik nachgebaut: Der echte
 * JAVASCRIPT-SYSTEM-ENGINE-<script>-Block der index.html wird extrahiert und in
 * einer VM mit DOM-Stubs ausgewertet. Geprueft werden die Tabellen selbst -
 * Vollstaendigkeit, Platzhalter-Treue und die Rechtstexte - sowie das Markup
 * der Sprachauswahl. Damit faellt auf, wenn eine Sprache einen Schluessel
 * verliert, ein Platzhalter verrutscht oder ein UI-Text nicht uebersetzt ist.
 *
 * Aufruf:  node selftest-i18n.js
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

/* --- Minimale DOM-Stubs (die Engine darf daran nicht scheitern) --- */
const elements = {};
function makeEl() {
    return {
        textContent: '', className: '', value: '', children: [], style: {},
        classList: { add() { }, remove() { }, toggle() { }, contains() { return false; } },
        setAttribute() { }, removeAttribute() { }, appendChild(child) { this.children.push(child); },
        getAttribute() { return null; }
    };
}
const sandbox = {
    console, JSON, Object, String, Number, Math, RegExp, Error, Array, Date, Set,
    setTimeout, clearTimeout, navigator: {},
    document: {
        getElementById: (id) => (elements[id] || (elements[id] = makeEl())),
        createElement: () => makeEl()
    },
    lucide: { createIcons() { } },
    requestAnimationFrame: (cb) => setTimeout(cb, 0)
};
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
function assertTrue(condition, label) {
    assertEqual(Boolean(condition), true, label);
}

const I18N = vm.runInContext('I18N', sandbox);
const I18N_LEGAL = vm.runInContext('I18N_LEGAL', sandbox);
const I18N_LANGUAGES = vm.runInContext('I18N_LANGUAGES', sandbox);
const I18N_CODES = vm.runInContext('I18N_CODES', sandbox);
const sourceKeys = Object.keys(vm.runInContext('I18N_SOURCE_KEYS', sandbox));

/* ---------------------------------------------------------------- *
 * 1) Sprachen und Vollstaendigkeit
 * ---------------------------------------------------------------- */
console.log('--- Sprachen ---');
assertEqual(I18N_CODES, ['de', 'en', 'ru', 'fr', 'es', 'ja'], 'Genau 6 Sprachen sind hinterlegt');
assertEqual(I18N_LANGUAGES.map((entry) => entry.label),
    ['Deutsch', 'English', 'Русский', 'Français', 'Español', '日本語'], 'Sprachnamen der Auswahlliste');
assertTrue(I18N_LANGUAGES.every((entry) => entry.flag && entry.flag.length > 0), 'Jede Sprache hat eine Flagge');

console.log('--- Vollstaendigkeit der Tabellen ---');
const reference = Object.keys(I18N.en).sort();
assertTrue(reference.length >= 100, 'Umfang der Tabelle (' + reference.length + ' Schluessel)');
assertEqual(sourceKeys.length, reference.length, 'Quell-Schluessel = englischer Schluesselbestand');
I18N_CODES.filter((code) => code !== 'de').forEach((code) => {
    const keys = Object.keys(I18N[code]).sort();
    const missing = reference.filter((key) => keys.indexOf(key) === -1);
    const extra = keys.filter((key) => reference.indexOf(key) === -1);
    assertEqual({ missing: missing, extra: extra }, { missing: [], extra: [] },
        'Tabelle ' + code + ' deckt alle Schluessel ab (' + keys.length + ' Schluessel)');
});
assertEqual(Object.keys(I18N.de).length, 0, 'Deutsch bleibt Quelltext (keine Doppelpflege)');
let emptyTranslations = 0;
sourceKeys.forEach((key) => {
    I18N_CODES.filter((code) => code !== 'de').forEach((code) => {
        const value = I18N[code][key];
        if (typeof value !== 'string' || value.trim() === '') {
            emptyTranslations++;
            console.log('FEHLER  Leere Uebersetzung fuer ' + JSON.stringify(key) + ' in ' + code);
        }
    });
});
assertEqual(emptyTranslations, 0, 'Keine leeren Uebersetzungen');


/* ---------------------------------------------------------------- *
 * 2) Platzhalter-Treue (z. B. {price}, {limit}, {n}, {a}, {b})
 * ---------------------------------------------------------------- */
console.log('--- Platzhalter ---');
const placeholderNames = (text) => (String(text).match(/\{(\w+)\}/g) || []).map((part) => part.slice(1, -1)).sort();
let placeholderErrors = 0;
reference.forEach((key) => {
    const expected = placeholderNames(key);
    I18N_CODES.filter((code) => code !== 'de').forEach((code) => {
        const actual = placeholderNames(I18N[code][key]);
        if (JSON.stringify(actual) !== JSON.stringify(expected)) {
            placeholderErrors++;
            console.log('FEHLER  Platzhalter weichen ab (' + code + '): ' + JSON.stringify(key) +
                ' -> ' + JSON.stringify(actual) + ' statt ' + JSON.stringify(expected));
        }
    });
});
assertEqual(placeholderErrors, 0, 'Platzhalter sind in allen Sprachen identisch');
assertTrue(reference.some((key) => key.indexOf('{price}') >= 0), 'Preis-Platzhalter {price} existiert');
assertTrue(reference.some((key) => key.indexOf('{limit}') >= 0), 'Limit-Platzhalter {limit} existiert');

/* ---------------------------------------------------------------- *
 * 3) Uebersetzung zur Laufzeit (de bleibt Deutsch, andere wechseln)
 * ---------------------------------------------------------------- */
console.log('--- Umschalten zur Laufzeit ---');
const translate = (lang, text, vars) => vm.runInContext(
    'currentLanguage = ' + JSON.stringify(lang) + '; translate(' + JSON.stringify(text) +
    (vars ? ', ' + JSON.stringify(vars) : '') + ')', sandbox);
assertEqual(translate('de', 'Premium'), 'Premium', 'Deutsch: Quelltext bleibt unveraendert');
assertEqual(translate('en', 'Verschlüsseln'), 'Encrypt', 'Englisch uebersetzt');
assertEqual(translate('ru', 'Verschlüsseln'), 'Зашифровать', 'Russisch uebersetzt');
assertEqual(translate('fr', 'Verschlüsseln'), 'Chiffrer', 'Franzoesisch uebersetzt');
assertEqual(translate('es', 'Verschlüsseln'), 'Cifrar', 'Spanisch uebersetzt');
assertEqual(translate('ja', 'Verschlüsseln'), '暗号化', 'Japanisch uebersetzt');
assertEqual(translate('en', '{n} / {limit} Wörter', { n: 12, limit: 50 }), '12 / 50 words', 'Platzhalter werden ersetzt');
assertEqual(translate('de', 'Testphase: noch {n} Tage', { n: 3 }), 'Testphase: noch 3 Tage', 'Deutsch mit Platzhaltern');
assertEqual(vm.runInContext('normalizeLanguage("de-AT")', sandbox), 'de', 'Region-Code wird auf die Sprache abgebildet');
assertEqual(vm.runInContext('normalizeLanguage("xx")', sandbox), 'de', 'Unbekannte Sprache faellt auf Deutsch zurueck');

/* ---------------------------------------------------------------- *
 * 4) Rechtstexte (AGB + Datenschutz) in allen Sprachen
 * ---------------------------------------------------------------- */
console.log('--- Rechtstexte ---');
assertEqual(Object.keys(I18N_LEGAL).sort(), ['de', 'en', 'es', 'fr', 'ja', 'ru'], 'Rechtstexte in 6 Sprachen');
I18N_CODES.forEach((code) => {
    const legal = I18N_LEGAL[code];
    assertTrue(Boolean(legal), 'Rechtstexte vorhanden (' + code + ')');
    ['terms', 'privacy'].forEach((tab) => {
        const body = legal[tab] || '';
        assertEqual((body.match(/<h4>/g) || []).length, 7, 'Abschnitte in ' + code + '.' + tab);
        assertTrue(body.indexOf('AES-256-GCM') >= 0, 'Krypto-Aussage in ' + code + '.' + tab);
        assertTrue(body.indexOf('CyberGuardSolution') >= 0 || body.indexOf('PixlBit') >= 0,
            'Bezug zum Anbieter/Produkt in ' + code + '.' + tab);
    });
});

/* ---------------------------------------------------------------- *
 * 5) Markup der Sprachauswahl und des Rechtliches-Dialogs
 * ---------------------------------------------------------------- */
console.log('--- Markup ---');
['id="language-button"', 'onclick="showLanguageModal()"', 'id="language-list"', 'id="language-modal"',
    'id="language-flag"', 'id="legal-button"', 'onclick="showLegalModal()"', 'id="legal-modal"',
    'id="legal-body"', 'id="legal-tab-terms"', 'id="legal-tab-privacy"'].forEach((needle) => {
    assertTrue(html.indexOf(needle) >= 0, 'Markup vorhanden: ' + needle);
});
assertTrue(script.indexOf('function showLanguageModal()') >= 0, 'Sprachdialog ist im Skript verankert');
assertTrue(script.indexOf('function showLegalModal()') >= 0, 'Rechtliches-Dialog ist im Skript verankert');
assertTrue(script.indexOf('I18N_STORAGE_KEY') >= 0, 'Sprachwahl wird lokal gespeichert (localStorage)');

/* ---------------------------------------------------------------- *
 * 6) Tarnfassade: Panik-Rueckmeldung in 6 Sprachen
 * ---------------------------------------------------------------- */
console.log('--- Tarnfassade (Panik-Code) ---');
const camouflage = html.match(/CAMOUFLAGE ENGINE \(Tarnfassade\)[\s\S]*?-->\s*<script>([\s\S]*?)<\/script>/);
assertTrue(Boolean(camouflage), 'Tarnfassade ist vorhanden');
const camoSrc = camouflage ? camouflage[1] : '';
assertTrue(camoSrc.indexOf('showFailedAttempt') >= 0, 'Panik-Code zeigt eine fehlgeschlagene Eingabe (kein Unlock)');
assertTrue(camoSrc.indexOf('showFailedAttempt') < camoSrc.indexOf('startUnlockTransition'), 'Fehlversuch wird vor dem Unlock behandelt');
['de', 'en', 'ru', 'fr', 'es', 'ja'].forEach((code) => {
    assertTrue(camoSrc.indexOf(code + ':') >= 0, 'Tarn-Fehlermeldung fuer ' + code);
});
assertTrue(camoSrc.indexOf('pixlbit_language') >= 0, 'Tarnfassade liest die gewaehlte Sprache');

console.log('');
if (failures === 0) {
    console.log('ALLE TESTS BESTANDEN');
} else {
    console.log(failures + ' TEST(S) FEHLGESCHLAGEN');
    process.exit(1);
}

assertEqual(vm.runInContext('normalizeLanguage(detectLanguage())', sandbox).length, 2, 'Systemsprache wird erkannt');
