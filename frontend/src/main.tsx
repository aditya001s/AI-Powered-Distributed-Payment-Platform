import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { App } from './App';
import './styles.css';

/**
 * Entry point.
 *
 * `StrictMode` is on, and it is worth saying why given what it does in development: it
 * mounts every component twice. That double-invocation is exactly what catches the class
 * of bug this dashboard is most exposed to — an effect that fires a request without
 * cleaning it up. Every loader here aborts its request on unmount, and StrictMode is what
 * proves it rather than leaving it as an intention.
 */
const container = document.getElementById('root');

if (!container) {
  // A missing root means index.html and this file disagree. Failing loudly names the
  // problem; the alternative is a blank page and a silent console.
  throw new Error('Root element #root was not found in the document.');
}

createRoot(container).render(
  <StrictMode>
    {/*
      The router sits outside App so App itself can be rendered inside a
      MemoryRouter by a test without nesting two routers.
    */}
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
);
