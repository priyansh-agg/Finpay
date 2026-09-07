import React from "react";
import { createRoot } from "react-dom/client";
import "./styles.css";
import "./checkout.css";
import { App } from "./App";
import { CheckoutPage } from "./CheckoutPage";

const payMatch = window.location.pathname.match(/^\/pay\/([0-9a-fA-F-]{36})\/?$/);

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    {payMatch ? <CheckoutPage orderId={payMatch[1]} /> : <App />}
  </React.StrictMode>
);
