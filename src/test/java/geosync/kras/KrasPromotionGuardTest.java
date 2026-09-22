package geosync.kras;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KrasPromotionGuardTest {
    @Test
    void pnuPromotionRequiresMatchingDatasetInDatabaseLookup() throws Exception {
        JdbcTemplate jdbc = transactionWithNoMatchingItem();
        var service = new KrasPnuIngestService(null, null, new KrasStagePromotionService(), null);
        assertThatThrownBy(() -> service.promote(jdbc, "46870", new LandInfoMapper(), 99L))
                .isInstanceOf(IllegalStateException.class);
    }

    private JdbcTemplate transactionWithNoMatchingItem() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            // A lookup missing dataset isolation must fail this test, before any write occurs.
            if (!sql.contains("dataset_code=?")) throw new AssertionError("dataset_code 격리 누락");
            return statement;
        });
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(false);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(invocation ->
                ((ConnectionCallback<?>) invocation.getArgument(0)).doInConnection(connection));
        return jdbc;
    }
}
