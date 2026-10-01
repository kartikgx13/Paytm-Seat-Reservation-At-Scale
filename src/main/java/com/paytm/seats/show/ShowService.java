package com.paytm.seats.show;

import com.paytm.seats.config.AppProperties;
import com.paytm.seats.show.ShowDtos.Counts;
import com.paytm.seats.show.ShowDtos.CreateShowRequest;
import com.paytm.seats.show.ShowDtos.SeatView;
import com.paytm.seats.show.ShowDtos.ShowView;
import com.paytm.seats.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ShowService {

    public record ShowRow(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats, Instant createdAt) {
    }

    private static final String SHOW_COLUMNS = "id, name, price_paise, per_user_limit, total_seats, created_at";

    private static final RowMapper<ShowRow> SHOW_MAPPER = (rs, i) -> new ShowRow(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getLong("price_paise"),
            rs.getInt("per_user_limit"),
            rs.getInt("total_seats"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;
    private final AppProperties props;

    public ShowService(JdbcTemplate jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Transactional
    public ShowView create(CreateShowRequest req) {
        List<String> labels = req.seats().stream().map(String::trim).toList();
        if (new HashSet<>(labels).size() != labels.size()) {
            throw ApiException.badRequest("seat labels must be unique");
        }
        int limit = req.perUserLimit() != null ? req.perUserLimit() : props.defaultPerUserLimit();
        UUID id = UUID.randomUUID();

        jdbc.update("INSERT INTO shows (id, name, price_paise, per_user_limit, total_seats) VALUES (?, ?, ?, ?, ?)",
                id, req.name(), req.pricePaise(), limit, labels.size());
        jdbc.execute((Connection c) -> {
            Array arr = c.createArrayOf("text", labels.toArray());
            try (var ps = c.prepareStatement(
                    "INSERT INTO seats (show_id, label, position) "
                            + "SELECT ?, t.label, t.ord FROM unnest(?::text[]) WITH ORDINALITY AS t(label, ord)")) {
                ps.setObject(1, id);
                ps.setArray(2, arr);
                ps.executeUpdate();
            }
            return null;
        });
        return get(id);
    }

    public Optional<ShowRow> find(UUID id) {
        return jdbc.query("SELECT " + SHOW_COLUMNS + " FROM shows WHERE id = ?", SHOW_MAPPER, id)
                .stream().findFirst();
    }

    public List<ShowRow> recentShows(int max) {
        return jdbc.query("SELECT " + SHOW_COLUMNS + " FROM shows ORDER BY created_at DESC LIMIT ?", SHOW_MAPPER, max);
    }

    /**
     * Seats and counts are derived from a single SELECT, i.e. one MVCC snapshot, so the reconciliation
     * invariant available + held + confirmed == total holds in every response, even mid-burst.
     */
    public ShowView get(UUID id) {
        ShowRow show = find(id).orElseThrow(() -> ApiException.notFound("show"));
        List<SeatView> seats = new ArrayList<>(show.totalSeats());
        int[] c = new int[3];
        jdbc.query("SELECT label, status FROM seats WHERE show_id = ? ORDER BY position", rs -> {
            String status = rs.getString(2);
            seats.add(new SeatView(rs.getString(1), status));
            switch (status) {
                case "available" -> c[0]++;
                case "held" -> c[1]++;
                default -> c[2]++;
            }
        }, id);
        Counts counts = new Counts(show.totalSeats(), c[0], c[1], c[2]);
        return new ShowView(show.id(), show.name(), show.pricePaise(), show.perUserLimit(), show.totalSeats(),
                show.createdAt(), counts, seats);
    }
}
