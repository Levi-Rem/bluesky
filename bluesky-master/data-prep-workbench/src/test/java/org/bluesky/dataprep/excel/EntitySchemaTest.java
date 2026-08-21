package org.bluesky.dataprep.excel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EntitySchemaTest {

    @Test
    void loadAllTraversesEveryBackendPage() {
        List<Integer> requestedPages = new ArrayList<>();
        EntitySchema<Integer> schema = new EntitySchema<>(
                "test", "test", Collections.emptyList(), Collections.emptyList(),
                (page, size) -> {
                    requestedPages.add(page);
                    int count = page == 0 ? size : 5;
                    List<Integer> rows = new ArrayList<>();
                    for (int index = 0; index < count; index++) {
                        rows.add(page * size + index);
                    }
                    return rows;
                },
                String::valueOf, row -> Collections.singletonList(String.valueOf(row)),
                (fields, existing) -> existing);

        assertThat(schema.loadAll()).hasSize(205);
        assertThat(requestedPages).isEqualTo(Arrays.asList(0, 1));
    }
}
