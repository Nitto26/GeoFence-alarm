import base64

html_data = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Mobile API Monitor | Send & Receive Station</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css">
    <style>
        .json-box {
            font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
            background-color: #020617;
            color: #38bdf8;
            border-radius: 0.75rem;
            overflow-x: auto;
            white-space: pre-wrap;
            word-break: break-all;
        }
    </style>
</head>
<body class="bg-slate-950 text-slate-100 min-h-screen p-4 md:p-8">

    <div class="max-w-6xl mx-auto space-y-6">
        
        <!-- Header -->
        <header class="bg-slate-900 border border-slate-800 rounded-2xl p-5 shadow-xl flex flex-col sm:flex-row sm:items-center justify-between gap-4">
            <div class="flex items-center gap-3">
                <div class="w-10 h-10 rounded-xl bg-blue-600 flex items-center justify-center text-white font-bold text-xl shadow-lg shadow-blue-500/20">
                    <i class="fas fa-exchange-alt"></i>
                </div>
                <div>
                    <h1 class="text-lg font-bold text-white tracking-wide">Mobile Location API Test Station</h1>
                    <p class="text-xs text-slate-400">Structured Send &amp; Receive Monitor for Android App</p>
                </div>
            </div>

            <div class="flex items-center gap-3">
                <div class="flex items-center gap-2 bg-slate-950 border border-slate-800 px-3 py-1.5 rounded-xl text-xs font-mono">
                    <span class="w-2.5 h-2.5 rounded-full bg-emerald-500 animate-pulse"></span>
                    <span class="text-emerald-400 font-semibold">Server Live (Port 8000)</span>
                </div>
                <button onclick="refreshAll()" class="px-3.5 py-1.5 bg-blue-600 hover:bg-blue-700 text-white rounded-xl text-xs font-bold transition flex items-center gap-1.5">
                    <i class="fas fa-sync-alt" id="btn-refresh-icon"></i> Refresh
                </button>
            </div>
        </header>

        <!-- Network Tunneling Box (Connecting across different networks) -->
        <div class="bg-gradient-to-r from-blue-950/90 to-indigo-950/90 border border-blue-800/60 rounded-2xl p-5 shadow-xl">
            <div class="flex items-center gap-2 text-blue-400 font-bold text-sm">
                <i class="fas fa-globe"></i> Connecting From Different Networks (4G/5G Mobile Data or Remote Wi-Fi)
            </div>
            <p class="text-xs text-slate-300 mt-1.5 leading-relaxed">
                If your phone and laptop are on <strong>different networks</strong>, run this 1-line command in your laptop PowerShell to create an instant public tunnel:
            </p>
            <div class="mt-3 bg-slate-950 px-4 py-2.5 rounded-xl font-mono text-xs text-emerald-400 border border-slate-800 flex items-center justify-between gap-3">
                <span class="select-all">ssh -R 80:localhost:8000 a.pinggy.io</span>
                <button onclick="copyPinggy(this)" class="px-2.5 py-1 bg-slate-800 hover:bg-slate-700 text-slate-300 hover:text-white rounded text-[11px] font-bold transition">
                    <i class="fas fa-copy mr-1"></i> Copy Command
                </button>
            </div>
            <p class="text-[11px] text-slate-400 mt-2">
                Copy the generated <span class="text-emerald-400 font-mono font-bold">https://xyz.a.pinggy.link</span> public URL into the mobile app's <strong>Server URL</strong> box on the Login screen!
            </p>
        </div>

        <!-- ======================================================== -->
        <!-- 1. SEND API (BACKEND -> MOBILE)                          -->
        <!-- ======================================================== -->
        <div class="bg-slate-900 border border-slate-800 rounded-2xl p-6 shadow-xl space-y-4">
            <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-2 border-b border-slate-800 pb-3">
                <div class="flex items-center gap-2.5">
                    <span class="bg-blue-600 text-white text-xs font-bold px-2.5 py-1 rounded-md">1. SEND API</span>
                    <span class="font-mono font-bold text-sm text-white">GET /api/mobile/jobs</span>
                    <span class="text-xs text-slate-400">(Backend &rarr; Mobile App)</span>
                </div>
                <div class="text-xs text-slate-400">
                    Last Mobile Fetch: <span class="font-bold font-mono text-blue-400" id="send-last-fetch">Never</span>
                </div>
            </div>

            <p class="text-xs text-slate-300">
                This payload is served to the mobile phone when the worker logs in (contains assigned jobs, polygon coordinates, and working dates):
            </p>

            <div class="relative">
                <button onclick="copyText('send-json-box', this)" class="absolute right-3 top-3 px-3 py-1 bg-slate-800 hover:bg-slate-700 border border-slate-700 rounded-lg text-xs font-bold text-slate-200 transition">
                    <i class="fas fa-copy"></i> Copy JSON
                </button>
                <pre class="json-box p-4 text-xs max-h-56" id="send-json-box">Loading jobs payload...</pre>
            </div>
        </div>

        <!-- ======================================================== -->
        <!-- 2. RECEIVE API (MOBILE -> BACKEND)                        -->
        <!-- ======================================================== -->
        <div class="bg-slate-900 border border-slate-800 rounded-2xl p-6 shadow-xl space-y-4">
            <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-2 border-b border-slate-800 pb-3">
                <div class="flex items-center gap-2.5">
                    <span class="bg-emerald-600 text-white text-xs font-bold px-2.5 py-1 rounded-md">2. RECEIVE API</span>
                    <span class="font-mono font-bold text-sm text-white">POST /api/mobile/location-events</span>
                    <span class="text-xs text-slate-400">(Mobile App &rarr; Backend)</span>
                </div>
                <div class="flex items-center gap-2 text-xs text-slate-400">
                    <span>Live Auto-Refresh:</span>
                    <span class="w-2 h-2 rounded-full bg-emerald-400 animate-ping"></span>
                    <span class="font-bold text-emerald-400 font-mono">1.5s</span>
                </div>
            </div>

            <p class="text-xs text-slate-300">
                Live events stream received from the mobile phone (Login, Location Service OFF/ON, GPS Pings, Entry/Exit):
            </p>

            <!-- Live Events Table -->
            <div class="bg-slate-950 border border-slate-800 rounded-xl overflow-hidden">
                <div class="overflow-x-auto">
                    <table class="w-full text-left text-xs">
                        <thead>
                            <tr class="bg-slate-900/90 text-slate-400 font-semibold border-b border-slate-800">
                                <th class="py-3 px-4">Time</th>
                                <th class="py-3 px-4">Event Type</th>
                                <th class="py-3 px-4">Job ID</th>
                                <th class="py-3 px-4">Coordinates (Lat, Lng)</th>
                                <th class="py-3 px-4">Status</th>
                                <th class="py-3 px-4 text-right">Payload</th>
                            </tr>
                        </thead>
                        <tbody class="divide-y divide-slate-800/80 text-slate-200" id="receive-events-tbody">
                            <tr>
                                <td colspan="6" class="py-12 text-center text-slate-500">
                                    <i class="fas fa-spinner fa-spin mr-2"></i> Waiting for mobile phone to send events...
                                </td>
                            </tr>
                        </tbody>
                    </table>
                </div>
            </div>

            <!-- Latest Raw Payload Viewer -->
            <div class="mt-4">
                <div class="flex items-center justify-between mb-1.5">
                    <span class="text-xs font-bold text-slate-300">Latest Incoming Raw Payload:</span>
                    <button onclick="copyText('receive-json-box', this)" class="text-xs text-blue-400 hover:text-blue-300 font-bold">
                        <i class="fas fa-copy"></i> Copy Payload
                    </button>
                </div>
                <pre class="json-box p-4 text-xs max-h-48 text-emerald-400" id="receive-json-box">// Waiting for phone event...</pre>
            </div>
        </div>

    </div>

    <!-- Live Polling Script -->
    <script>
        async function fetchSendApi() {
            try {
                const res = await fetch("/api/mobile/jobs?worker_id=WORKER-1001");
                const data = await res.json();
                document.getElementById("send-json-box").textContent = JSON.stringify(data, null, 2);
            } catch (e) {
                document.getElementById("send-json-box").textContent = "Error fetching jobs: " + e;
            }
        }

        async function fetchReceivedEvents() {
            try {
                const res = await fetch("/api/admin/events?limit=15");
                const data = await res.json();
                const events = data.events || [];

                const tbody = document.getElementById("receive-events-tbody");
                if (!tbody) return;

                if (events.length === 0) {
                    tbody.innerHTML = `<tr><td colspan="6" class="py-12 text-center text-slate-500">No events received yet. Tap Login or toggle GPS on your phone!</td></tr>`;
                    return;
                }

                // Update latest payload box
                const latest = events[0];
                document.getElementById("receive-json-box").textContent = JSON.stringify(latest, null, 2);

                tbody.innerHTML = events.map(ev => {
                    let badgeClass = "bg-blue-900/60 text-blue-300 border-blue-700";
                    let label = ev.event_type;

                    if (ev.event_type === "location_service_off") {
                        badgeClass = "bg-rose-900/80 text-rose-200 border-rose-600 font-bold animate-pulse";
                        label = "LOCATION OFF (Sabotage)";
                    } else if (ev.event_type === "location_service_on") {
                        badgeClass = "bg-emerald-900/60 text-emerald-300 border-emerald-700";
                        label = "LOCATION ON";
                    } else if (ev.event_type === "login") {
                        badgeClass = "bg-purple-900/60 text-purple-300 border-purple-700 font-bold";
                        label = "LOGIN EVENT";
                    } else if (ev.event_type === "entry") {
                        badgeClass = "bg-emerald-900/60 text-emerald-300 border-emerald-700";
                        label = "ENTRY";
                    } else if (ev.event_type === "exit") {
                        badgeClass = "bg-amber-900/60 text-amber-300 border-amber-700";
                        label = "EXIT";
                    }

                    const timeStr = new Date(ev.timestamp).toLocaleTimeString();
                    const latLng = (ev.latitude && ev.longitude) ? `${ev.latitude.toFixed(4)}, ${ev.longitude.toFixed(4)}` : "—";

                    return `
                        <tr class="hover:bg-slate-900/70 transition border-b border-slate-800/80">
                            <td class="py-3 px-4 font-mono text-slate-400">${timeStr}</td>
                            <td class="py-3 px-4">
                                <span class="px-2.5 py-1 rounded-md text-[11px] border ${badgeClass}">${label}</span>
                            </td>
                            <td class="py-3 px-4 font-mono text-slate-300 font-bold">${ev.job_id || 'Device'}</td>
                            <td class="py-3 px-4 font-mono text-slate-400">${latLng}</td>
                            <td class="py-3 px-4"><span class="text-emerald-400 font-bold text-[11px]"><i class="fas fa-check-circle mr-1"></i> 200 OK</span></td>
                            <td class="py-3 px-4 text-right">
                                <button onclick='showEventDetails(${JSON.stringify(JSON.stringify(ev))})' class="text-blue-400 hover:underline font-mono text-xs">View</button>
                            </td>
                        </tr>
                    `;
                }).join("");

            } catch (e) {
                console.error("Error fetching live events:", e);
            }
        }

        async function fetchLastSendTimestamp() {
            try {
                const res = await fetch("/api/admin/transactions?direction=BACKEND_TO_MOBILE&limit=1");
                const data = await res.json();
                if (data.transactions && data.transactions.length > 0) {
                    const tx = data.transactions[0];
                    const time = new Date(tx.created_at).toLocaleTimeString();
                    document.getElementById("send-last-fetch").innerText = `${time} (${tx.duration_ms}ms, 200 OK)`;
                }
            } catch (e) {}
        }

        function showEventDetails(jsonStr) {
            try {
                const parsed = JSON.parse(jsonStr);
                document.getElementById("receive-json-box").textContent = JSON.stringify(parsed, null, 2);
            } catch (e) {}
        }

        function copyText(elementId, btn) {
            const text = document.getElementById(elementId).textContent;
            navigator.clipboard.writeText(text);
            const original = btn.innerHTML;
            btn.innerHTML = `<i class="fas fa-check"></i> Copied!`;
            setTimeout(() => btn.innerHTML = original, 1500);
        }

        function copyPinggy(btn) {
            navigator.clipboard.writeText("ssh -R 80:localhost:8000 a.pinggy.io");
            const original = btn.innerHTML;
            btn.innerHTML = `<i class="fas fa-check text-emerald-400"></i> Copied!`;
            setTimeout(() => btn.innerHTML = original, 1500);
        }

        function refreshAll() {
            const icon = document.getElementById("btn-refresh-icon");
            if (icon) icon.classList.add("fa-spin");
            fetchSendApi();
            fetchReceivedEvents();
            fetchLastSendTimestamp();
            setTimeout(() => icon.classList.remove("fa-spin"), 500);
        }

        document.addEventListener("DOMContentLoaded", () => {
            fetchSendApi();
            fetchReceivedEvents();
            fetchLastSendTimestamp();
            setInterval(() => {
                fetchReceivedEvents();
                fetchLastSendTimestamp();
            }, 1500);
        });
    </script>
</body>
</html>
"""

with open(r'..\admin-panel\frontend\index.html', 'w', encoding='utf-8') as f:
    f.write(html_data)
print("UPDATED_INDEX_SUCCESSFULLY")
