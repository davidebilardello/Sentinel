'use strict';
let session = null, schema = [], online = false, nextWrite = 0, stateTimer = null;
const writes = new Map();
const post = event => NativeYouFs.event(JSON.stringify(event));
const decode = text => Uint8Array.from(atob(text), c => c.charCodeAt(0));
const rx = {
    listener: null,
    addEventListener(_, callback) { this.listener = callback; },
    removeEventListener(_, callback) { if (this.listener === callback) this.listener = null; },
    async startNotifications() {}
};
const tx = {
    properties: { write: true },
    writeValueWithResponse(packet) {
        return new Promise((resolve, reject) => {
            const id = ++nextWrite; writes.set(id, { resolve, reject });
            NativeYouFs.packet(id, btoa(String.fromCharCode(...packet)));
        });
    }
};
function engineWriteDone(id, error) {
    const pending = writes.get(id); if (!pending) return;
    writes.delete(id); if (error) pending.reject(Error(error)); else pending.resolve();
}
function state() {
    if (!session) return;
    const availability = session.writeAvailability(schema);
    const age = session.lastTelemetryAt === null ? Infinity : Date.now() - session.lastTelemetryAt;
    post({ type: 'state', authenticated: online && session.authenticated && !session.closed, busy: Boolean(session.writeBusy), fresh: age >= 0 && age <= 3000,
        protocol: String(session.version), canCommand: online && availability.allowed, reason: availability.reason,
        values: Array.from(session.values.values(), dp => ({ id: dp.id, type: dp.type, value: dp.value })) });
}
async function engineStart(encoded) {
    engineClose();
    try {
        const profile = JSON.parse(new TextDecoder().decode(decode(encoded)));
        schema = TuyaBLE.parseSchema(profile);
        session = new TuyaBLE.Session(tx, rx, profile, {
            data: state,
            error: error => { online = false; state(); post({ type: 'error', message: error.message }); }
        });
        await session.start(); online = true; state();
        stateTimer = setInterval(state, 1000);
        await engineQuery();
    } catch (error) { post({ type: 'error', message: error.message }); }
}
function engineReceive(encoded) {
    const packet = decode(encoded);
    rx.listener?.({ target: { value: new DataView(packet.buffer, packet.byteOffset, packet.byteLength) } });
}
async function engineCommand(id, input) {
    if (!session || !online) return;
    try {
        const definition = schema.find(dp => dp.id === id);
        const operation = session.write(definition, input, schema); state();
        const confirmed = await operation;
        post({ type: 'result', dp: id, confirmed, message: confirmed ? 'Impostazione confermata dai dati del veicolo.' : 'Impostazione non confermata. Nessuna ritrasmissione automatica.' });
    } catch (error) { post({ type: 'result', dp: id, confirmed: false, message: error.message }); }
    finally { state(); }
}
async function engineQuery() {
    if (!session || !online) return;
    try { await session.query(); }
    catch (error) { post({ type: 'notice', message: error.message }); }
    finally { state(); }
}
function engineClose() {
    online = false; clearInterval(stateTimer); stateTimer = null;
    session?.close(); session = null; schema = [];
    for (const pending of writes.values()) pending.reject(Error('Sessione Bluetooth chiusa.'));
    writes.clear();
}
