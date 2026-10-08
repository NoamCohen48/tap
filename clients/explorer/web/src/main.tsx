import { lazy, StrictMode, Suspense } from "react";
import { createRoot } from "react-dom/client";
const App = lazy(() =>
  import("./App").then((module) => ({ default: module.App })),
);
const root = document.getElementById("root");
if (!root) throw new Error("Missing application root");
createRoot(root).render(
  <StrictMode>
    <Suspense fallback={<p role="status">Loading evidence workbench…</p>}>
      <App />
    </Suspense>
  </StrictMode>,
);
