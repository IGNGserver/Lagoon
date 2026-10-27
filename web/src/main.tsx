import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { stripKeyFragment } from "./lib/clientAccess";
import { registerServiceWorker } from "./lib/pwa";
import "./styles.css";

stripKeyFragment(window.location, window.history);
registerServiceWorker();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
