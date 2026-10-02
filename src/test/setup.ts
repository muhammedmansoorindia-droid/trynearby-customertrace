import '@testing-library/jest-dom/vitest';

// jsdom lacks these; the scanner hook feature-detects them, tests must too.
if (!('mediaDevices' in navigator)) {
  Object.defineProperty(navigator, 'mediaDevices', { value: undefined, writable: true });
}

// Deterministic clipboard for "Copy Code" assertions.
Object.assign(navigator, {
  clipboard: { writeText: vi.fn().mockResolvedValue(undefined) },
});

// crypto.randomUUID is used for record ids.
if (!globalThis.crypto?.randomUUID) {
  Object.defineProperty(globalThis.crypto, 'randomUUID', {
    value: () => '00000000-0000-4000-8000-000000000000',
    writable: true,
  });
}