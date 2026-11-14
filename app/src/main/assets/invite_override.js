(function () {
	if (window.__wwlInviteOverrideInstalled) {
		return;
	}
	window.__wwlInviteOverrideInstalled = true;

	var qrMargin = 4;
	var qrCellSize = 8;
	var loopbackNote = "No LAN address detected. Connect to Wi-Fi or Ethernet, or open this server through its network address, to invite friends.";

	function advertisedHost() {
		var advertised = window.WWL_ADVERTISE;
		if (typeof advertised === "string") {
			var value = advertised.trim().replace(/^https?:\/\//i, "").replace(/\/+$/, "");
			if (value) {
				return value;
			}
		}
		return location.host;
	}

	function inviteUrl() {
		var injected = window.WWL_INVITE_URL;
		if (typeof injected === "string" && injected.trim()) {
			return injected.trim();
		}
		return "http://" + location.host;
	}

	function isLoopbackHost(host) {
		var value = host.toLowerCase();
		return value === "localhost" ||
			value.startsWith("localhost:") ||
			value === "[::1]" ||
			value.startsWith("[::1]:") ||
			value.startsWith("127.");
	}

	function renderQrPng(text) {
		if (typeof window.qrcode !== "function") {
			return null;
		}
		var qr = window.qrcode(0, "M");
		qr.addData(text);
		qr.make();
		var count = qr.getModuleCount();
		var size = (count + qrMargin * 2) * qrCellSize;
		var canvas = document.createElement("canvas");
		canvas.width = size;
		canvas.height = size;
		var context = canvas.getContext("2d");
		if (!context) {
			return null;
		}
		context.fillStyle = "#ffffff";
		context.fillRect(0, 0, size, size);
		context.fillStyle = "#000000";
		for (var row = 0; row < count; row += 1) {
			for (var col = 0; col < count; col += 1) {
				if (!qr.isDark(row, col)) {
					continue;
				}
				context.fillRect(
					(col + qrMargin) * qrCellSize,
					(row + qrMargin) * qrCellSize,
					qrCellSize,
					qrCellSize,
				);
			}
		}
		return canvas.toDataURL("image/png");
	}

	function refresh() {
		var urlNode = document.getElementById("share-invite-url");
		var qrNode = document.getElementById("share-invite-qr");
		var note = document.getElementById("share-invite-note");
		if (!urlNode || !qrNode) {
			return;
		}
		var url = inviteUrl();
		urlNode.textContent = url;
		var png = renderQrPng(url);
		if (png) {
			qrNode.src = png;
			qrNode.alt = "QR code to join at " + url;
			qrNode.hidden = false;
		}
		if (note) {
			var loopback = isLoopbackHost(advertisedHost());
			note.hidden = !loopback;
			note.textContent = loopbackNote;
		}
	}

	function inviteMessage() {
		var userNode = document.getElementById("overview-user");
		var username = userNode && userNode.textContent ? userNode.textContent.trim() : "";
		if (!username) {
			username = "a player";
		}
		return "You've been invited by " + username +
			" to play Words with Learners together.\n\nGo to " + inviteUrl() +
			" in your Web Browser to join!";
	}

	function copyInvite(event) {
		var target = event.target;
		if (!(target instanceof Element)) {
			return;
		}
		var button = target.closest("#share-invite-copy");
		if (!button) {
			return;
		}
		event.preventDefault();
		event.stopImmediatePropagation();
		var message = inviteMessage();
		if (window.WWLAndroid && typeof window.WWLAndroid.copyInvite === "function") {
			window.WWLAndroid.copyInvite(message);
		} else if (window.WWLAndroid && typeof window.WWLAndroid.copyText === "function") {
			window.WWLAndroid.copyText(message);
		} else if (navigator.clipboard && navigator.clipboard.writeText) {
			navigator.clipboard.writeText(message);
		}
	}

	window.__wwlRefreshInvite = refresh;
	document.addEventListener("click", copyInvite, true);

	var dialog = document.getElementById("share-invite-dialog");
	if (dialog && typeof MutationObserver === "function") {
		new MutationObserver(function () {
			if (dialog.open) {
				refresh();
			}
		}).observe(dialog, { attributes: true, attributeFilter: ["open"] });
	}
})();
