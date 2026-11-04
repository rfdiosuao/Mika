"use strict";
const pairing = document.querySelector(".pairing-code");
const qrBox = document.getElementById("pairing-qr");
if (pairing && qrBox && typeof qrcode === "function") {
  const payload = "mika://relay?url=" + encodeURIComponent(location.origin) +
    "&code=" + encodeURIComponent(pairing.dataset.code || "");
  try {
    const qr = qrcode(0, "M");
    qr.addData(payload);
    qr.make();
    qrBox.innerHTML = qr.createSvgTag({ cellSize: 4, margin: 8, scalable: true });
    qrBox.hidden = false;
  } catch (_) {
    qrBox.hidden = true;
  }
}
const form = document.getElementById("pairing-form");
const key = document.getElementById("owner-key");
const dialog = document.getElementById("confirm-dialog");
const revoke = document.getElementById("revoke");
key.addEventListener("invalid", () => key.setCustomValidity(key.dataset.requiredMessage));
key.addEventListener("input", () => key.setCustomValidity(""));
if (revoke && typeof dialog.showModal === "function") {
  form.addEventListener("submit", event => {
    if (event.submitter === revoke && document.getElementById("confirm-value").value !== "yes") {
      event.preventDefault();
      dialog.returnValue = "";
      dialog.showModal();
    }
  });
  dialog.addEventListener("close", () => {
    if (dialog.returnValue === "confirm") {
      document.getElementById("confirm-value").value = "yes";
      form.requestSubmit(revoke);
    } else {
      revoke.focus();
    }
  });
}
