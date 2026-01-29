package com.digitalgroup.holape.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Standard REST pagination response wrapper.
 *
 * This DTO provides a consistent, framework-agnostic pagination format
 * following REST best practices. The frontend adapter layer is responsible
 * for transforming this to any UI-specific format (e.g., DataTables).
 *
 * @param <T> The type of items in the data list
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PagedResponse<T> {

    private List<T> data;
    private Meta meta;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Meta {
        private long totalItems;
        private int page;
        private int pageSize;
        private int totalPages;
    }

    /**
     * Create a PagedResponse from a Spring Data Page object
     */
    public static <T> PagedResponse<T> fromPage(Page<T> page) {
        return PagedResponse.<T>builder()
                .data(page.getContent())
                .meta(Meta.builder()
                        .totalItems(page.getTotalElements())
                        .page(page.getNumber() + 1) // Convert 0-based to 1-based
                        .pageSize(page.getSize())
                        .totalPages(page.getTotalPages())
                        .build())
                .build();
    }

    /**
     * Create a PagedResponse from a simple list (no pagination)
     */
    public static <T> PagedResponse<T> fromList(List<T> list) {
        return PagedResponse.<T>builder()
                .data(list)
                .meta(Meta.builder()
                        .totalItems(list.size())
                        .page(1)
                        .pageSize(list.size())
                        .totalPages(1)
                        .build())
                .build();
    }

    /**
     * Create a PagedResponse with explicit pagination info
     */
    public static <T> PagedResponse<T> of(List<T> data, long totalItems, int page, int pageSize) {
        int totalPages = (int) Math.ceil((double) totalItems / pageSize);
        return PagedResponse.<T>builder()
                .data(data)
                .meta(Meta.builder()
                        .totalItems(totalItems)
                        .page(page)
                        .pageSize(pageSize)
                        .totalPages(totalPages)
                        .build())
                .build();
    }
}
