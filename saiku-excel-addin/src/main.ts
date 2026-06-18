import { mount } from "svelte";
import App from "./ui/App.svelte";

declare const Office: any;
Office.onReady(() => {
  mount(App, { target: document.getElementById("app")! });
});
