/* Tuya BLE: legacy 4/5 and the verified YouFs-A P4 14/15 profile. */
(function (root, factory) {
    if (typeof module === 'object' && module.exports) module.exports = factory(require('./vendor/crypto-js-4.2.0.js'));
    else root.TuyaBLE = factory(root.CryptoJS);
})(typeof globalThis !== 'undefined' ? globalThis : this, function (C) {
    'use strict';
    const bytes = value => value instanceof Uint8Array ? value : new Uint8Array(value);
    const join = (...parts) => { const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0)); let at = 0; for (const p of parts) { out.set(p, at); at += p.length; } return out; };
    const wordArray = data => C.lib.WordArray.create(bytes(data));
    const fromWords = data => Uint8Array.from({ length: data.sigBytes }, (_, i) => (data.words[i >>> 2] >>> (24 - (i % 4) * 8)) & 255);
    const utf8 = text => new TextEncoder().encode(text);
    const md5 = data => fromWords(C.MD5(wordArray(data)));
    const fail = text => { throw new Error(text); };
    const view = data => new DataView(data.buffer, data.byteOffset, data.byteLength);

    function credentials(input) {
        if (!input || typeof input !== 'object') fail('Credenziali Tuya mancanti.');
        const uuid = input.uuid;
        const deviceId = input.deviceId ?? input.device_id ?? input.devId;
        const localKey = input.localKey ?? input.local_key;
        const secKey = input.secKey ?? input.secretKey ?? input.sec_key;
        const securityMode = input.securityMode ?? (secKey ? 'new' : 'legacy');
        if (typeof uuid !== 'string' || !/^[A-Za-z0-9]{16}$/.test(uuid)) fail('Questo client richiede un UUID Tuya di 16 caratteri.');
        if (typeof deviceId !== 'string' || !/^[A-Za-z0-9]{1,22}$/.test(deviceId)) fail('Device ID Tuya non valido. Non usare l’ID Bluetooth del browser.');
        if (typeof localKey !== 'string' || !/^[\x21-\x7e]{16}$/.test(localKey)) fail('La local key deve contenere 16 caratteri ASCII.');
        if (!['legacy', 'new'].includes(securityMode)) fail('Profilo di sicurezza Tuya non valido.');
        if (securityMode === 'new' && (typeof secKey !== 'string' || !/^[\x21-\x7e]{16}$/.test(secKey))) fail('La sicurezza nuova richiede la Sec Key di 16 caratteri del dispositivo.');
        return { uuid, deviceId, localKey, securityMode, ...(securityMode === 'new' ? { secKey } : {}) };
    }
    function crc16(data) {
        let crc = 0xffff;
        for (const b of data) { crc ^= b; for (let i = 0; i < 8; i++) crc = crc & 1 ? (crc >>> 1) ^ 0xa001 : crc >>> 1; }
        return crc;
    }
    const loginKey = localKey => md5(utf8(localKey.slice(0, 6)));
    const sessionKey = (localKey, random) => md5(join(utf8(localKey.slice(0, 6)), random));
    const newLoginKey = (localKey, secKey) => md5(utf8(localKey + secKey));
    const newSessionKey = (localKey, secKey, random) => md5(join(utf8(localKey + secKey), random));
    function encodeFrame(sequence, responseTo, code, data, key, flag, iv = fromWords(C.lib.WordArray.random(16))) {
        if (!Number.isInteger(sequence) || sequence < 1 || sequence > 0xffffffff || data.length > 900) fail('Frame Tuya fuori limite.');
        const plain = new Uint8Array(Math.ceil((14 + data.length) / 16) * 16);
        const v = view(plain);
        v.setUint32(0, sequence); v.setUint32(4, responseTo); v.setUint16(8, code); v.setUint16(10, data.length);
        plain.set(data, 12); v.setUint16(12 + data.length, crc16(plain.subarray(0, 12 + data.length)));
        const cipher = C.AES.encrypt(wordArray(plain), wordArray(key), { iv: wordArray(iv), mode: C.mode.CBC, padding: C.pad.NoPadding });
        return join(Uint8Array.of(flag), iv, fromWords(cipher.ciphertext));
    }
    function decodeFrame(payload, key) {
        if (payload.length < 33 || payload.length > 1024 || (payload.length - 17) % 16) fail('Lunghezza frame Tuya non valida.');
        const plain = fromWords(C.AES.decrypt(C.lib.CipherParams.create({ ciphertext: wordArray(payload.subarray(17)) }), wordArray(key),
            { iv: wordArray(payload.subarray(1, 17)), mode: C.mode.CBC, padding: C.pad.NoPadding }));
        const v = view(plain), length = v.getUint16(10), end = 12 + length;
        if (end + 2 > plain.length || plain.length - (end + 2) > 15 || v.getUint16(end) !== crc16(plain.subarray(0, end))) fail('Decifratura Tuya non valida: chiave, formato o CRC errati.');
        return { sequence: v.getUint32(0), responseTo: v.getUint32(4), code: v.getUint16(8), data: plain.slice(12, end) };
    }
    function varint(value) {
        const result = [];
        do { const b = value & 127; value >>>= 7; result.push(b | (value ? 128 : 0)); } while (value);
        return Uint8Array.from(result);
    }
    function readVarint(data, at) {
        let n = 0;
        for (let i = 0; i < 4 && at < data.length; i++) { const b = data[at++]; n += (b & 127) * 2 ** (7 * i); if (!(b & 128)) return [n, at]; }
        fail('Frammento Tuya troncato o fuori limite.');
    }
    function packets(payload, version = 3) {
        const result = []; let at = 0;
        for (let index = 0; at < payload.length; index++) {
            const header = index === 0 ? join(varint(0), varint(payload.length), Uint8Array.of(version << 4)) : varint(index);
            const chunk = payload.subarray(at, at + 20 - header.length); result.push(join(header, chunk)); at += chunk.length;
        }
        return result;
    }
    class Reassembler {
        constructor() { this.reset(); }
        reset() { this.buffer = new Uint8Array(); this.length = 0; this.next = 0; }
        feed(data) {
            try {
                let [index, at] = readVarint(data, 0);
                if (index === 0) {
                    if (this.length) fail('Messaggio Tuya precedente incompleto: riconnetti il dispositivo.');
                    this.reset(); [this.length, at] = readVarint(data, at);
                    if (this.length < 33 || this.length > 1024 || at >= data.length || ![2, 3, 4].includes(data[at] >>> 4)) fail('Versione o lunghezza del messaggio Tuya non supportata.');
                    at++;
                } else if (!this.length || index !== this.next) fail('Frammento Tuya mancante o fuori ordine.');
                this.buffer = join(this.buffer, data.subarray(at)); this.next++;
                if (this.buffer.length > this.length) fail('Frammento Tuya oltre la lunghezza dichiarata.');
                if (this.buffer.length !== this.length) return null;
                const result = this.buffer; this.reset(); return result;
            } catch (error) { this.reset(); throw error; }
        }
    }
    function parseDP(data, wide = false) {
        const result = []; let at = 0;
        while (at < data.length) {
            if (data.length - at < (wide ? 4 : 3)) fail('Intestazione DP troncata.');
            const id = data[at++], type = data[at++];
            const length = wide ? data[at++] * 256 + data[at++] : data[at++];
            if (!id || type > 5 || at + length > data.length) fail('DP non valido o incompleto.');
            const raw = data.slice(at, at + length); at += length;
            let value;
            if (type === 1) { if (length !== 1 || raw[0] > 1) fail('DP booleano non valido.'); value = Boolean(raw[0]); }
            else if (type === 2) { if (length !== 4) fail('DP numerico non valido.'); value = view(raw).getInt32(0); }
            else if (type === 3) value = new TextDecoder('utf-8', { fatal: true }).decode(raw);
            else if (type === 4) { if (length !== 1) fail('DP enum non valido.'); value = raw[0]; }
            else if (type === 5) { if (![1, 2, 4].includes(length)) fail('DP bitmap non valido.'); value = raw.reduce((n, b) => n * 256 + b, 0); }
            else value = Array.from(raw, b => b.toString(16).padStart(2, '0')).join('');
            result.push({ id, type, value, raw: Array.from(raw), receivedAt: Date.now() });
        }
        return result;
    }
    function parseSchema(input) {
        let list = input?.schema ?? input;
        if (typeof list === 'string') { try { list = JSON.parse(list); } catch (_) { fail('Schema JSON non valido.'); } }
        if (!Array.isArray(list) || list.length > 255) fail('Lo schema deve essere una lista di DP.');
        const seen = new Set();
        return list.map(dp => {
            const id = Number(dp.id), p = dp.property;
            if (!Number.isInteger(id) || id < 1 || id > 255 || seen.has(id) || !p || typeof p.type !== 'string') fail('Definizione DP incompleta o duplicata.');
            seen.add(id);
            const item = { id, code: typeof dp.code === 'string' ? dp.code : '', name: String(dp.name || dp.code || 'DP ' + id).slice(0, 100), mode: dp.mode,
                type: p.type, scale: p.scale ?? 0, min: p.min, max: p.max, step: p.step ?? 1, range: p.range, labels: p.label, maxlen: p.maxlen, unit: typeof p.unit === 'string' ? p.unit : '' };
            if (item.type === 'value' && (!Number.isInteger(item.scale) || item.scale < 0 || item.scale > 6 || !Number.isInteger(item.min) || !Number.isInteger(item.max) || item.min < -2147483648 || item.max > 2147483647 || item.min > item.max || !Number.isInteger(item.step) || item.step <= 0)) fail('Intervallo DP numerico non valido.');
            if (item.type === 'enum' && (!Array.isArray(item.range) || !item.range.length || item.range.length > 256 || !item.range.every(x => typeof x === 'string'))) fail('Valori DP enum non validi.');
            if (item.type === 'string' && item.mode === 'rw' && (!Number.isInteger(item.maxlen) || item.maxlen < 1 || item.maxlen > 255)) fail('Lunghezza massima DP stringa non valida.');
            return item;
        });
    }
    function encodeDP(def, input, current, absolute = false) {
        if (def.mode !== 'rw' || (!absolute && (!current || current.id !== def.id || Date.now() - current.receivedAt < 0 || Date.now() - current.receivedAt > 3000))) fail('Serve uno stato recente del DP in lettura/scrittura.');
        let raw, type;
        if (def.type === 'bool') { type = 1; if (typeof input !== 'boolean') fail('Valore booleano richiesto.'); raw = Uint8Array.of(Number(input)); }
        else if (def.type === 'value') {
            if ((typeof input !== 'number' && typeof input !== 'string') || String(input).trim() === '') fail('Valore numerico richiesto.');
            type = 2; const n = Number(input) * 10 ** def.scale, rounded = Math.round(n);
            if (!Number.isFinite(n) || Math.abs(n - rounded) > 1e-6 || rounded < def.min || rounded > def.max || (rounded - def.min) % def.step) fail('Valore fuori dai limiti dello schema.');
            raw = new Uint8Array(4); view(raw).setInt32(0, rounded);
        } else if (def.type === 'enum') { type = 4; const n = def.range.indexOf(input); if (n < 0) fail('Valore enum non previsto.'); raw = Uint8Array.of(n); }
        else if (def.type === 'string' && absolute) {
            type = 3;
            if (typeof input !== 'string') fail('Testo richiesto per questo DP.');
            raw = utf8(input);
            if (!Number.isInteger(def.maxlen) || raw.length > def.maxlen) fail('Testo oltre il limite dello schema.');
        }
        else fail('Questo tipo di DP resta in sola lettura: non sono definiti comandi privati o maschere ipotizzate.');
        if (current && (current.id !== def.id || type !== current.type)) fail('Tipo ricevuto diverso da quello dello schema.');
        return join(Uint8Array.of(def.id, type, raw.length), raw);
    }

    class Session {
        constructor(tx, rx, input, handlers = {}) {
            this.tx = tx; this.rx = rx; this.credentials = credentials(input); this.handlers = handlers;
            this.modern = this.credentials.securityMode === 'new';
            this.login = this.modern ? newLoginKey(this.credentials.localKey, this.credentials.secKey) : loginKey(this.credentials.localKey);
            this.key = null; this.authenticated = false; this.closed = false;
            this.sequence = 0; this.peerSequence = 0; this.revision = 0; this.dpSequence = 0; this.version = this.modern ? 4 : 3;
            this.pending = new Map(); this.values = new Map(); this.confirmations = new Set(); this.reassembler = new Reassembler(); this.early = [];
            this.lastTelemetryAt = null; this.lastQueryAt = null; this.lastQueryError = null; this.querying = null; this.refreshTimer = null;
            this.sendQueue = Promise.resolve(); this.receiveQueue = Promise.resolve();
            this.listener = event => {
                const v = event.target.value, data = new Uint8Array(v.buffer, v.byteOffset, v.byteLength).slice();
                this.receiveQueue = this.receiveQueue.then(() => this.receive(data)).catch(error => { this.close(); this.handlers.error?.(error); });
            };
        }
        async start() {
            this.rx.addEventListener('characteristicvaluechanged', this.listener);
            try {
                await this.rx.startNotifications();
                const info = await this.request(0, this.modern ? Uint8Array.of(0, 20) : new Uint8Array(), true);
                if (info.data.length < 46 || ![3, 4].includes(info.data[2])) fail('Informazioni o versione Tuya non supportate.');
                if (!info.data[5]) fail('Dispositivo non associato: completare prima l’associazione nell’app originale.');
                if (this.modern) {
                    if (info.data.length < 77 || info.data[2] !== 4 || (info.data[4] & 0x1a) || (info.data[54] & 6) !== 6) fail('Il firmware richiede un formato di sicurezza diverso da quello verificato.');
                    const actualId = new TextDecoder('utf-8', { fatal: true }).decode(info.data.subarray(55, 77)).replace(/\0.*$/, '');
                    if (actualId !== this.credentials.deviceId) fail('Device ID del veicolo diverso da quello delle credenziali.');
                }
                this.version = info.data[2];
                this.key = this.modern ? newSessionKey(this.credentials.localKey, this.credentials.secKey, info.data.slice(6, 12)) : sessionKey(this.credentials.localKey, info.data.slice(6, 12));
                const data = new Uint8Array(this.modern ? 82 : 44);
                data.set(utf8(this.credentials.uuid + this.credentials.localKey.slice(0, 6) + this.credentials.deviceId));
                if (this.modern) { data[45] = 1; data.set(utf8(this.credentials.localKey + this.credentials.secKey), 46); }
                const pairing = await this.request(1, data);
                if (pairing.data.length !== 1 || ![0, 2].includes(pairing.data[0])) fail('Il dispositivo ha rifiutato le credenziali Tuya.');
                this.authenticated = true;
                this.handlers.authenticated?.({ protocol: info.data[2] + '.' + info.data[3], commandsSupported: this.commandsSupported });
                for (const frame of this.early) await this.handleFrame(frame);
                this.early = [];
                if (this.modern) this.refreshTimer = setInterval(() => this.refresh(), 1000);
            } catch (error) { this.close(); throw error; }
        }
        send(code, data, responseTo = 0, login = false, reservedSequence) {
            const sequence = reservedSequence ?? ++this.sequence;
            const operation = this.sendQueue.then(async () => {
                if (this.closed || (!login && !this.key)) fail('Sessione Tuya chiusa o non pronta.');
                const flag = this.modern ? (login ? 14 : 15) : (login ? 4 : 5);
                const payload = encodeFrame(sequence, responseTo, code, data, login ? this.login : this.key, flag);
                for (const packet of packets(payload, this.modern ? 2 : this.version)) {
                    if (this.closed) fail('Sessione Tuya chiusa.');
                    if (this.modern && this.tx.properties.write) await this.tx.writeValueWithResponse(packet);
                    else if (this.tx.properties.writeWithoutResponse) await this.tx.writeValueWithoutResponse(packet);
                    else if (this.tx.properties.write) await this.tx.writeValueWithResponse(packet);
                    else fail('Caratteristica Tuya non scrivibile.');
                }
            });
            this.sendQueue = operation.catch(() => {});
            return operation;
        }
        request(code, data = new Uint8Array(), login = false) {
            if (this.closed || this.pending.size) return Promise.reject(new Error('Sessione Tuya chiusa o richiesta già in corso.'));
            const sequence = ++this.sequence;
            return new Promise((resolve, reject) => {
                const timer = setTimeout(() => { this.pending.delete(sequence); reject(new Error('Nessuna risposta Tuya: verifica credenziali, formato di autenticazione e collegamento.')); }, 8000);
                this.pending.set(sequence, { code, resolve, reject, timer });
                this.send(code, data, 0, login, sequence).catch(error => { clearTimeout(timer); this.pending.delete(sequence); reject(error); });
            });
        }
        async query() {
            if (!this.authenticated || this.closed) fail('Autenticazione Tuya necessaria.');
            if (this.querying) return this.querying;
            this.lastQueryAt = Date.now();
            this.querying = (async () => {
                try {
                    const response = await this.request(3);
                    if (response.data.length !== 1 || response.data[0] !== 0) fail('Richiesta dati rifiutata dal dispositivo.');
                    this.lastQueryError = null;
                } catch (error) { this.lastQueryError = error.message; throw error; }
            })();
            try { return await this.querying; } finally { this.querying = null; }
        }
        async refresh() {
            if (!this.modern || !this.authenticated || this.closed || this.writeBusy || this.pending.size) return;
            const now = Date.now();
            if (this.lastTelemetryAt !== null && now - this.lastTelemetryAt < 1500) return;
            if (this.lastQueryAt !== null && now - this.lastQueryAt < 2000) return;
            // Idle devices may report only changes. Query state without refreshing timestamps from the ACK.
            try { await this.query(); } catch (_) { /* The controls show lastQueryError while telemetry remains stale. */ }
        }
        get commandsSupported() { return this.version === 3 || (this.modern && this.version === 4); }
        writeAvailability(schema, now = Date.now()) {
            const blocked = reason => ({ allowed: false, reason });
            if (!this.authenticated || this.closed) return blocked('Sessione Bluetooth non autenticata.');
            if (!this.commandsSupported) return blocked('Scritture non supportate da questo profilo.');
            const speeds = Array.isArray(schema) ? schema.filter(dp => ['speed', 'cur_speed', 'speed_current'].includes(dp.code) && dp.type === 'value') : [];
            const speed = speeds.length === 1 ? this.values.get(speeds[0].id) : null;
            if (!speed || speed.type !== 2) return blocked('In attesa del dato velocità del veicolo.');
            if (speed.value !== 0) return blocked('Comando sospeso: la velocità del veicolo non è zero.');
            if (this.modern) {
                // The report's transaction ID is not a dense packet counter; only validated, fresh telemetry maintains this state.
                const age = this.lastTelemetryAt === null ? Infinity : now - this.lastTelemetryAt;
                if (age < 0 || age > 3000) return blocked('Comandi sospesi: flusso dati non aggiornato. ' +
                    (this.querying ? 'Aggiornamento dello stato in corso…' : this.lastQueryError || 'Aggiornamento automatico attivo; puoi anche premere Aggiorna dati.'));
            } else if (now - speed.receivedAt < 0 || now - speed.receivedAt > 3000) return blocked('Comando sospeso: serve un dato velocità recente.');
            return { allowed: true, reason: 'Velocità zero confermata · ricezione dati attiva.' };
        }
        async write(definition, input, schema) {
            if (!this.authenticated || !this.commandsSupported) fail('Scritture disponibili solo su sessione Tuya legacy v3 o v4 nuova autenticata.');
            if (this.writeBusy) fail('Attendi la conferma del comando precedente.');
            this.writeBusy = true;
            try {
            if (!Array.isArray(schema) || !schema.includes(definition)) fail('DP non presente nello schema caricato.');
            const availability = this.writeAvailability(schema);
            if (!availability.allowed) fail(availability.reason);
            const payload = encodeDP(definition, input, this.values.get(definition.id), this.modern);
            const expected = parseDP(payload)[0];
            const beforeRevision = this.values.get(definition.id)?.revision || 0;
            if (this.modern) return await this.writeV4(payload, expected, beforeRevision);
            const response = await this.request(2, payload);
            if (response.data.length !== 1 || response.data[0] !== 0) fail('Comando rifiutato dal dispositivo.');
            // An ACK alone does not confirm that the requested setting was applied.
            const confirmed = this.values.get(definition.id);
            if (confirmed?.revision > beforeRevision && confirmed.type === expected.type && confirmed.value === expected.value) return true;
            return await new Promise(resolve => {
                const waiter = { id: definition.id, type: expected.type, value: expected.value, beforeRevision, resolve };
                waiter.timer = setTimeout(() => { this.confirmations.delete(waiter); resolve(false); }, 3000);
                this.confirmations.add(waiter);
            });
            } finally { this.writeBusy = false; }
        }
        writeV4(tlv, expected, beforeRevision) {
            const sequence = ++this.sequence, dpSn = ++this.dpSequence;
            const header = new Uint8Array(5); view(header).setUint32(1, dpSn);
            const body = join(header, Uint8Array.of(tlv[0], tlv[1], 0, tlv[2]), tlv.subarray(3));
            return new Promise((resolve, reject) => {
                const finish = (value, error) => {
                    clearTimeout(waiter.timer); this.confirmations.delete(waiter); this.pending.delete(sequence);
                    if (error) reject(error); else resolve(value);
                };
                const waiter = { id: expected.id, type: expected.type, value: expected.value, beforeRevision, resolve: value => finish(value) };
                waiter.timer = setTimeout(() => finish(false), 30000);
                this.confirmations.add(waiter);
                this.pending.set(sequence, { code: 39, isWrite: true, reject: error => finish(false, error), timer: waiter.timer,
                    onAck: frame => {
                        const data = frame.data;
                        if (frame.code !== 39 || (data.length !== 4 && data.length !== 6)) {
                            finish(false, new Error('Formato ACK DP non riconosciuto (comando ' + frame.code + ', ' + data.length + ' byte).')); return;
                        }
                        // TuyaOpen v4 echoes version + R_SN before the status; the older SDK uses 4 bytes.
                        if (data.length === 6 && (data[0] !== header[0] || view(data).getUint32(1) !== dpSn)) {
                            finish(false, new Error('ACK DP non corrispondente al comando inviato.')); return;
                        }
                        const status = data[data.length - 1];
                        if (status !== 0) finish(false, new Error('Comando rifiutato dal veicolo (stato ' + status + ').'));
                    } });
                this.send(39, body, 0, false, sequence).catch(error => finish(false, error));
            });
        }
        async receive(chunk) {
            if (this.closed) return;
            const payload = this.reassembler.feed(chunk); if (!payload) return;
            const flag = payload[0];
            const key = flag === (this.modern ? 14 : 4) && !this.authenticated ? this.login : flag === (this.modern ? 15 : 5) ? this.key : null;
            if (!key) fail('Modalità di autenticazione/cifratura Tuya diversa dal profilo legacy supportato.');
            const frame = decodeFrame(payload, key);
            if (!frame.sequence || frame.sequence <= this.peerSequence) fail('Sequenza Tuya duplicata o fuori ordine.');
            this.peerSequence = frame.sequence;
            const pending = this.pending.get(frame.responseTo);
            const isReport = this.modern && (frame.code === 0x8006 || frame.code === 0x8007);
            if (pending && !isReport) {
                if (pending.isWrite) { pending.onAck(frame); return; }
                if (frame.code !== pending.code) fail('Risposta Tuya non corrispondente alla richiesta.');
                clearTimeout(pending.timer); this.pending.delete(frame.responseTo); pending.resolve(frame);
            }
            if (frame.responseTo && !isReport) return;
            if (!this.authenticated) { if (this.modern && this.early.length < 32) this.early.push(frame); return; }
            await this.handleFrame(frame);
        }
        acceptValues(values) {
            if (values.length) this.lastTelemetryAt = Date.now();
            for (const dp of values) {
                dp.revision = ++this.revision; this.values.set(dp.id, dp);
                for (const c of this.confirmations) if (c.id === dp.id && c.type === dp.type && c.value === dp.value && dp.revision > c.beforeRevision) {
                    clearTimeout(c.timer); this.confirmations.delete(c); c.resolve(true);
                }
            }
            this.handlers.data?.(values);
        }
        async handleFrame(frame) {
            if (this.modern) {
                if ([0x8011, 0x8012, 0x8013].includes(frame.code)) {
                    const now = new Date(), zone = new Uint8Array(2); view(zone).setInt16(0, Math.round(-now.getTimezoneOffset() * 100 / 60));
                    const clock = frame.code === 0x8011 ? utf8(String(Date.now()).padStart(13, '0')) : Uint8Array.of(now.getFullYear() - 2000, now.getMonth() + 1, now.getDate(), now.getHours(), now.getMinutes(), now.getSeconds(), now.getDay());
                    await this.send(frame.code, join(clock, zone), frame.code === 0x8013 ? 0 : frame.sequence); return;
                }
                if (frame.code === 0x8006 || frame.code === 0x8007) {
                    let data = frame.data;
                    if (data.length < 7 || data[0] !== 0 || ![0, 1].includes(data[5] & 15)) fail('Formato del report v4 non verificato.');
                    const header = data.subarray(0, 7); data = data.subarray(7);
                    if (frame.code === 0x8007) {
                        const skip = data[0] === 0 ? 14 : data[0] === 1 ? 5 : 0;
                        if (!skip || data.length < skip) fail('Timestamp report v4 non valido.');
                        data = data.subarray(skip);
                    }
                    const values = parseDP(data, true);
                    this.acceptValues(values);
                    if (!(header[5] & 128)) await this.send(frame.code, join(header, Uint8Array.of(0)), frame.sequence);
                    return;
                }
                if (frame.code >= 0x8001 && frame.code <= 0x8005) fail('Questo formato DP non è verificato nel profilo v4.');
                return;
            }
            if (frame.code === 0x8001 || frame.code === 0x8003) {
                let data = frame.data;
                if (frame.code === 0x8003) {
                    const skip = data[0] === 0 ? 14 : data[0] === 1 ? 5 : 0;
                    if (!skip || data.length < skip) fail('Timestamp DP non supportato.');
                    data = data.subarray(skip);
                }
                const values = parseDP(data);
                this.acceptValues(values); await this.send(frame.code, new Uint8Array(), frame.sequence);
            } else if ([0x8004, 0x8005, 0x8006, 0x8007].includes(frame.code)) fail('Formato DP firmato/v4 non ancora decodificato per questo firmware.');
        }
        close() {
            if (this.closed) return;
            this.closed = true; this.authenticated = false; this.rx.removeEventListener('characteristicvaluechanged', this.listener);
            clearInterval(this.refreshTimer); this.refreshTimer = null;
            for (const p of this.pending.values()) { clearTimeout(p.timer); p.reject(new Error('Sessione Tuya chiusa.')); }
            for (const c of this.confirmations) { clearTimeout(c.timer); c.resolve(false); }
            this.confirmations.clear();
            this.pending.clear(); this.login?.fill(0); this.key?.fill(0); this.credentials = null; this.values.clear(); this.reassembler.reset();
            this.early = [];
            this.lastTelemetryAt = null;
        }
    }
    return { credentials, crc16, loginKey, sessionKey, newLoginKey, newSessionKey, encodeFrame, decodeFrame, packets, Reassembler, parseDP, parseSchema, encodeDP, Session };
});
