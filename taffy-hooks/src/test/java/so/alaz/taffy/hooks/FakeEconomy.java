package so.alaz.taffy.hooks;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// A Vault economy that keeps each balance as a double and adds and subtracts it with no rounding, as an economy
// plugin that stores double does. A withdraw is never refused for a short balance, so a balance can go below zero.
// answerNext makes the next withdraw or deposit give that response and move no money, and feeNext makes the next
// withdraw take that much on top of its amount. Once credit is called the economy allows an overdraft up to that
// limit: has answers true for an amount the balance and the limit cover together, and a withdraw beyond them is
// refused. It names its currency Dollar and Dollars and formats an amount as a dollar sign and the double. Of the
// calls for an account, only those that take an OfflinePlayer and no world are supported.
@SuppressWarnings("deprecation")
final class FakeEconomy implements Economy {

    final List<String> calls = new ArrayList<>();

    private final Map<UUID, Double> balances = new HashMap<>();
    private final String name;
    private final int digits;
    private EconomyResponse answer;
    private double fee;
    private Double credit;

    FakeEconomy(String name, int digits) {
        this.name = name;
        this.digits = digits;
    }

    void set(UUID player, double balance) {
        balances.put(player, balance);
    }

    double stored(UUID player) {
        return balances.getOrDefault(player, 0.0);
    }

    void answerNext(EconomyResponse.ResponseType type, String message) {
        answer = new EconomyResponse(0, 0, type, message);
    }

    void feeNext(double fee) {
        this.fee = fee;
    }

    void credit(double limit) {
        credit = limit;
    }

    private EconomyResponse answered() {
        EconomyResponse given = answer;
        answer = null;
        return given;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int fractionalDigits() {
        return digits;
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return balances.containsKey(player.getUniqueId());
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        calls.add("getBalance");
        return stored(player.getUniqueId());
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        calls.add("has " + amount);
        return stored(player.getUniqueId()) + (credit == null ? 0 : credit) >= amount;
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        calls.add("withdrawPlayer " + amount);
        EconomyResponse given = answered();
        if (given != null) {
            return given;
        }
        if (credit != null && stored(player.getUniqueId()) + credit < amount) {
            return new EconomyResponse(0, stored(player.getUniqueId()), EconomyResponse.ResponseType.FAILURE,
                "Insufficient funds");
        }
        double left = stored(player.getUniqueId()) - amount - fee;
        fee = 0;
        balances.put(player.getUniqueId(), left);
        return new EconomyResponse(amount, left, EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        calls.add("depositPlayer " + amount);
        EconomyResponse given = answered();
        if (given != null) {
            return given;
        }
        double held = stored(player.getUniqueId()) + amount;
        balances.put(player.getUniqueId(), held);
        return new EconomyResponse(amount, held, EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public String format(double amount) {
        calls.add("format " + amount);
        return "$" + amount;
    }

    @Override
    public String currencyNamePlural() {
        return "Dollars";
    }

    @Override
    public String currencyNameSingular() {
        return "Dollar";
    }

    @Override
    public boolean hasAccount(String name) {
        throw new UnsupportedOperationException("hasAccount");
    }

    @Override
    public boolean hasAccount(String name, String world) {
        throw new UnsupportedOperationException("hasAccount");
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String world) {
        throw new UnsupportedOperationException("hasAccount");
    }

    @Override
    public double getBalance(String name) {
        throw new UnsupportedOperationException("getBalance");
    }

    @Override
    public double getBalance(String name, String world) {
        throw new UnsupportedOperationException("getBalance");
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        throw new UnsupportedOperationException("getBalance");
    }

    @Override
    public boolean has(String name, double amount) {
        throw new UnsupportedOperationException("has");
    }

    @Override
    public boolean has(String name, String world, double amount) {
        throw new UnsupportedOperationException("has");
    }

    @Override
    public boolean has(OfflinePlayer player, String world, double amount) {
        throw new UnsupportedOperationException("has");
    }

    @Override
    public EconomyResponse withdrawPlayer(String name, double amount) {
        throw new UnsupportedOperationException("withdrawPlayer");
    }

    @Override
    public EconomyResponse withdrawPlayer(String name, String world, double amount) {
        throw new UnsupportedOperationException("withdrawPlayer");
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String world, double amount) {
        throw new UnsupportedOperationException("withdrawPlayer");
    }

    @Override
    public EconomyResponse depositPlayer(String name, double amount) {
        throw new UnsupportedOperationException("depositPlayer");
    }

    @Override
    public EconomyResponse depositPlayer(String name, String world, double amount) {
        throw new UnsupportedOperationException("depositPlayer");
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String world, double amount) {
        throw new UnsupportedOperationException("depositPlayer");
    }

    @Override
    public EconomyResponse createBank(String name, String owner) {
        throw new UnsupportedOperationException("createBank");
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer owner) {
        throw new UnsupportedOperationException("createBank");
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        throw new UnsupportedOperationException("deleteBank");
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        throw new UnsupportedOperationException("bankBalance");
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        throw new UnsupportedOperationException("bankHas");
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        throw new UnsupportedOperationException("bankWithdraw");
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        throw new UnsupportedOperationException("bankDeposit");
    }

    @Override
    public EconomyResponse isBankOwner(String name, String owner) {
        throw new UnsupportedOperationException("isBankOwner");
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer owner) {
        throw new UnsupportedOperationException("isBankOwner");
    }

    @Override
    public EconomyResponse isBankMember(String name, String member) {
        throw new UnsupportedOperationException("isBankMember");
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer member) {
        throw new UnsupportedOperationException("isBankMember");
    }

    @Override
    public List<String> getBanks() {
        throw new UnsupportedOperationException("getBanks");
    }

    @Override
    public boolean createPlayerAccount(String name) {
        throw new UnsupportedOperationException("createPlayerAccount");
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        throw new UnsupportedOperationException("createPlayerAccount");
    }

    @Override
    public boolean createPlayerAccount(String name, String world) {
        throw new UnsupportedOperationException("createPlayerAccount");
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String world) {
        throw new UnsupportedOperationException("createPlayerAccount");
    }
}
