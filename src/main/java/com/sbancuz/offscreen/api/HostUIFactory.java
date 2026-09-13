package com.sbancuz.offscreen.api;

/**
 * Creates a {@link HostUI} from a screen object.
 *
 * @param <H> the HostUI type this factory produces
 */
@FunctionalInterface
public interface HostUIFactory<H extends HostUI> {

    H create(Object screen);
}
