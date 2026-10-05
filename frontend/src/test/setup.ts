/**
 * Vitest setup, loaded before every test file.
 *
 * Registers jest-dom's matchers against Vitest's `expect`, which is what lets a
 * test assert `toBeDisabled()` or `toHaveTextContent()` instead of poking at DOM
 * properties. The `/vitest` entry point is the one that wires into Vitest rather
 * than Jest; importing the plain package registers nothing here and the matchers
 * then fail at runtime with a confusing "not a function".
 */
import '@testing-library/jest-dom/vitest';
