const { contextBridge, ipcRenderer } = require('electron');
const argumentNumber = (prefix, fallback) => {
  const value = Number(process.argv.find(arg => arg.startsWith(prefix))?.slice(prefix.length));
  return Number.isFinite(value) && value > 0 ? value : fallback;
};
// Only immutable resource counts, aggregate memory totals and a scoped busy flag cross the sandbox.
contextBridge.exposeInMainWorld('nxrDesktop', {
  capabilities: Object.freeze({
    hardwareConcurrency: argumentNumber('--nxr-cpu=', 2),
    deviceMemory: argumentNumber('--nxr-memory=', 4),
  }),
  getPerformanceMetrics: () => ipcRenderer.invoke('nxr:performance-metrics'),
  setProcessingBusy: busy => { if (typeof busy === 'boolean') ipcRenderer.send('nxr:processing', busy); },
});
