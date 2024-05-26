package net.affell.reseaux.tp1;

import net.affell.reseaux.tp1.listeners.ClientListener;
import net.affell.reseaux.tp1.listeners.ServerListener;
import net.kio.its.client.Client;
import net.kio.its.client.SocketWorker;
import net.kio.its.responsesystem.IResponseWorker;
import net.kio.its.server.Server;
import net.kio.its.server.ServerWorker;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class Routeur {

    private Server server;
    private Client client;
    public boolean waitReply;

    private final CommandManager commandManager;
    private final Map<? super IResponseWorker, String> routage;
    private final Map<String, ? super IResponseWorker> cache;

    public Routeur(String[] args) {
        this.commandManager = new CommandManager(this);
        this.routage = new HashMap<>();
        this.cache = new HashMap<>();
        if (args.length != 2) {
            System.err.println("Veuillez spécifier le nom du routeur et le port d'écoute (1024-65535) en paramètre");
            return;
        }
        String name = args[0];
        int port = -1;
        try {
            port = Integer.parseInt(args[1]);
        } catch (NumberFormatException ignored) {
        }
        if (port < 1024 || port > 65535) {
            System.err.println("Veuillez spécifier le nom du routeur et le port d'écoute (1024-65535) en paramètre");
            return;
        }

        try {
            // Création des instances Serveur et Client
            this.server = new Server(name, port, false);
            server.getLogger().setEnable(false);
            // Interception des événements du serveur
            server.getEventsManager().registerListener(new ServerListener(this));
            // Démarrage de l'écoute des connexions entrantes
            server.startSocketListener();
            System.out.println("Routeur " + name + " à l'écoute sur le port " + port);

            this.client = new Client("Client", false);
            client.getLogger().setEnable(false);
            // Interception des événements du client
            client.getEventsManager().registerListener(new ClientListener(this));

            // Lecture de l'entrée de l'utilisateur
            Scanner scanner = new Scanner(System.in);
            String line;
            while ((line = scanner.nextLine()) != null) {
                handleCommand(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void handleCommand(String line) {
        // Gère les commandes de l'utilisateur
        String[] s = line.split(" ");
        switch (s[0]) {
            case "help" -> System.out.println("Liste des commandes :\n\nhelp -> Affiche cette aide\nlist -> Liste les connexions établies entrantes et sortantes\nping <routeur> -> Envoie une requête ping sur le routeur précisé\nconnect <ip> <port> -> Établit une connexion sur le routeur cible précisé\nsend <routeur> <message> -> Envoie un message au routeur spécifié\ntraceroute <routeur> -> Affiche la route pour communiquer avec le routeur précisé\nshowroutes -> Affiche la table de routage dynamique");
            case "ping" -> {
                if (s.length != 2) {
                    System.err.println("Usage: ping <routeur>");
                }

                System.out.println("PING " + s[1] + " :");
                waitReply = true; // Le routeur attend une réponse
                // On envoie le PING avec la logique de parcours en profondeur si la cible n'est pas un voisin direct
                dispatchCommand(server.getName(), s[1], Collections.singletonList(server.getName()), "ping", server.getName(), s[1], server.getName(), String.valueOf(System.nanoTime()));
                // On attend 3s
                int i = 0;
                while (waitReply) {
                    if (i == 30) {
                        break;
                    }
                    try {
                        //noinspection BusyWait
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                    }
                    i++;
                }
                if (waitReply) {
                    System.err.println("Impossible de joindre le routeur " + s[1]);
                }
            }
            case "list" -> {
                if (s.length != 1) {
                    System.err.println("Usage: list");
                    return;
                }
                if (!client.getSocketWorkerList().isEmpty()) {
                    System.out.println("Connexions sortantes :");
                    for (SocketWorker worker : client.getSocketWorkerList()) {
                        System.out.println(routage.get(worker) + " : " + worker.getTargetIp() + " " + worker.getTargetPort());
                    }
                }
                if (!server.getClients().isEmpty()) {
                    System.out.println("Connexions entrantes :");
                    for (ServerWorker worker : server.getClients()) {
                        System.out.println(routage.get(worker) + " : " + worker.getClientSocket().getInetAddress().getHostAddress() + " " + worker.getClientMacAddress());
                    }
                }
            }
            case "connect" -> {
                try {
                    if (s.length != 3) {
                        throw new NumberFormatException();
                    }
                    String ip = s[1];
                    int port = Integer.parseInt(s[2]);
                    try {
                        // Connexion à un routeur avec l'instance du Client
                        SocketWorker worker = client.addSocketWorker(ip, port);
                        System.out.print("Tentative de connexion...\r");
                        worker.startWorker();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                } catch (NumberFormatException e) {
                    System.err.println("Usage: connect <ip> <port>");
                }
            }
            case "send" -> {
                try {
                    if (s.length < 3) {
                        throw new NumberFormatException();
                    }
                    String message = Arrays.stream(s).skip(2).collect(Collectors.joining(" "));

                    // Recherche du routeur cible dans les voisins directs
                    IResponseWorker worker = null;
                    for (Object o : routage.keySet()) {
                        if (s[1].equals(routage.get((IResponseWorker) o))) {
                            worker = (IResponseWorker) o;
                            break;
                        }
                    }

                    // Si la cible n'est pas un voisin direct, le message ne peut pas être envoyé
                    if (worker == null) {
                        System.err.println("Nom de routeur invalide");
                        return;
                    }
                    worker.sendCommand("message", message);

                } catch (NumberFormatException e) {
                    System.err.println("Usage: send <nom> <message>");
                }


            }
            case "traceroute" -> {
                if (s.length != 2) {
                    System.err.println("Usage: traceroute <routeur>");
                }
                System.out.println("Route to " + s[1] + " :");
                // Envoi de la commande trace qui va atteindre la cible et renvoyer le chemin parcouru
                dispatchCommand(server.getName(), s[1], Collections.singletonList(server.getName()), "trace", server.getName(), s[1], server.getName());
            }
            case "showroutes" -> routage.forEach((worker, name) -> {
                String ip = "undefined";
                if (worker instanceof ServerWorker serverWorker) {
                    ip = serverWorker.getClientSocket().getInetAddress().getHostAddress() + ":" + serverWorker.getClientSocket().getPort();
                } else if (worker instanceof SocketWorker socketWorker) {
                    ip = socketWorker.getSocket().getInetAddress().getHostAddress() + ":" + socketWorker.getTargetPort();
                }
                System.out.println(name + " -> " + ip);
            });
        }
    }

    /**
     * Gère l'envoi d'une commande à n'importe quel routeur sur le réseau
     * @param source Envoyeur
     * @param target Cible
     * @param path Chemin déjà emprunté par la commande
     * @param command Commande
     * @param args Arguments
     */
    public void dispatchCommand(String source, String target, List<String> path, String command, String... args) {
        boolean broadcast = !routage.containsValue(target); // Vrai si la cible n'est pas un voisin direct
        if (broadcast && cache.containsKey(target) && !path.contains(routage.getOrDefault(((IResponseWorker) cache.get(target)), ""))) {
            // Cache hit, on connait quel routeur dans nos voisins direct permet d'atteindre la cible de facon optimale
            ((IResponseWorker) cache.get(target)).sendCommand(command, args);
            return;
        }
        // Cache miss, on va "broadcast" la commande à tous nos voisins qui ne sont pas dans le chemin déjà emprunté de la commande
        routage.keySet().forEach(w -> {
            if (!path.contains(routage.get((IResponseWorker) w)) && !source.equals(routage.get((IResponseWorker) w)) && (broadcast || target.equals(routage.get((IResponseWorker) w)))) {
                ((IResponseWorker) w).sendCommand(command, args);
            }
        });
    }

    public void updateCache(List<String> path) {
        // Si on reçoit une commande envoyée avec "dispatchCommand", on peut lire le chemin déjà emprunté et en déduire les chemins optimaux pour rejoindre les routeurs mentionnés dans ce chemin
        if (path.size() <= 1) return;
        // On détermine l'instance IResponseWorker (Client ou Serveur) du dernier routeur dans le chemin
        IResponseWorker worker = null;
        for (Object o : routage.keySet()) {
            if (path.get(path.size() - 1).equals(routage.get((IResponseWorker) o))) {
                worker = (IResponseWorker) o;
                break;
            }
        }
        if (worker == null) {
            return;
        }
        // On attribue à tous les autres routeurs du chemin le dernier routeur comme point de passage optimal
        for (int i = path.size() - 2; i >= 0; i--) {
            if (!cache.containsKey(path.get(i))) {
                cache.put(path.get(i), worker);
            }
        }
    }

    public static void main(String[] args) {
        new Routeur(args);
    }

    public Client getClient() {
        return client;
    }

    public Server getServer() {
        return server;
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }

    public Map<? super IResponseWorker, String> getRoutage() {
        return routage;
    }

    public Map<String, ? super IResponseWorker> getCache() {
        return cache;
    }
}
