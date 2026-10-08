import React from "react";
import ReactDOM from "react-dom/client";
import { createBrowserRouter, createRoutesFromElements, RouterProvider } from "react-router-dom";
import { appRouteElements } from "./app/App";
import "./app/styles.css";
import "./app/console.css";

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    <RouterProvider router={createBrowserRouter(createRoutesFromElements(appRouteElements()))} />
  </React.StrictMode>,
);
