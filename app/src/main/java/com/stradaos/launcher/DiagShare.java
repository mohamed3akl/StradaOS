package com.stradaos.launcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Enviar el diagnóstico al celular por Wi-Fi (también a un iPhone, que no recibe archivos por Bluetooth desde
 * Android): la carpeta StradaDiag se empaca en un ZIP y la pantalla lo sirve con un servidor web pequeño en la red
 * local (el Wi-Fi de la casa o el punto de acceso del celular). El celular lo abre con un código QR.
 *
 * Solo responde a la dirección con el código al azar, una conexión a la vez, y se apaga sola a los 15 minutos.
 */
class DiagShare {

    static final long LIFETIME_MS = 15 * 60 * 1000;
    private static final int PORT = 8765;

    private static ServerSocket server;
    private static File file;
    private static String token, fileName;
    private static long stopAt;

    /** Empaca la carpeta en un ZIP (sin comprimir los APK, que ya vienen comprimidos). */
    static File zip(File dir, File out) throws Exception {
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(out));
        try {
            File[] fs = dir.listFiles();
            byte[] buf = new byte[65536];
            if (fs != null) for (File f : fs) {
                if (!f.isFile()) continue;
                ZipEntry e = new ZipEntry(dir.getName() + "/" + f.getName());
                e.setTime(f.lastModified());
                z.putNextEntry(e);
                InputStream in = new FileInputStream(f);
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
                } finally {
                    in.close();
                }
                z.closeEntry();
            }
        } finally {
            z.close();
        }
        return out;
    }

    /** Dirección IPv4 de la pantalla en la red local (Wi-Fi o punto de acceso). null si no hay red. */
    static String localIp() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName() == null ? "" : ni.getName();
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    // primero el Wi-Fi (wlan, ap); los datos móviles (rmnet, ccmni) no llegan al celular
                    if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("eth")) return ip;
                    if (a.isSiteLocalAddress() && fallback == null) fallback = ip;
                }
            }
        } catch (Throwable ignored) { }
        return fallback;
    }

    /** Empieza a compartir el archivo. Devuelve la dirección para el QR (http://IP:puerto/código/nombre.zip). */
    static synchronized String start(File zip, String ip) throws Exception {
        stop();
        file = zip;
        fileName = zip.getName();
        token = randomToken();
        stopAt = System.currentTimeMillis() + LIFETIME_MS;
        ServerSocket ss;
        try { ss = new ServerSocket(PORT); } catch (Throwable e) { ss = new ServerSocket(0); }   // puerto ocupado: otro
        ss.setSoTimeout(5000);
        server = ss;
        final ServerSocket me = ss;
        new Thread(new Runnable() { public void run() { loop(me); } }, "DiagShare").start();
        return "http://" + ip + ":" + ss.getLocalPort() + "/" + token + "/" + fileName;
    }

    static synchronized void stop() {
        if (server != null) try { server.close(); } catch (Throwable ignored) { }
        server = null;
    }

    static synchronized boolean running() { return server != null; }

    private static void loop(ServerSocket ss) {
        while (true) {
            synchronized (DiagShare.class) {
                if (server != ss) return;
                if (System.currentTimeMillis() > stopAt) { stop(); return; }
            }
            Socket s;
            try {
                s = ss.accept();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            } catch (Throwable e) {
                return;     // cerrado
            }
            try { serve(s); } catch (Throwable ignored) { }
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    private static void serve(Socket s) throws Exception {
        s.setSoTimeout(15000);
        BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "US-ASCII"));
        String line = r.readLine();
        if (line == null) return;
        while (true) {      // se ignoran las cabeceras
            String h = r.readLine();
            if (h == null || h.length() == 0) break;
        }
        String[] p = line.split(" ");
        String method = p.length > 0 ? p[0] : "", path = p.length > 1 ? p[1] : "";
        File f;
        String want;
        synchronized (DiagShare.class) { f = file; want = "/" + token + "/" + fileName; }
        OutputStream out = s.getOutputStream();
        if (f == null || !f.exists() || !(path.equals(want) || path.equals("/" + token + "/") || path.equals("/" + token))) {
            out.write(("HTTP/1.0 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: 9\r\nConnection: close\r\n\r\nNot found").getBytes("US-ASCII"));
            out.flush();
            return;
        }
        String head = "HTTP/1.0 200 OK\r\nContent-Type: application/zip\r\nContent-Length: " + f.length()
                + "\r\nContent-Disposition: attachment; filename=\"" + f.getName() + "\"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes("US-ASCII"));
        if (!"HEAD".equals(method)) {
            InputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                in.close();
            }
        }
        out.flush();
    }

    private static String randomToken() {
        String abc = "abcdefghjkmnpqrstuvwxyz23456789";
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) b.append(abc.charAt(rnd.nextInt(abc.length())));
        return b.toString();
    }
}
