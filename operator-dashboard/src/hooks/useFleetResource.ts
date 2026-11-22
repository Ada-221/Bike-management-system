import { useEffect, useState } from "react";
import { FleetApiError } from "../api/fleet";

type Loader<T> = (signal?: AbortSignal) => Promise<T>;
interface Resource<T> {
  data: T | null;
  error: FleetApiError | null;
  loading: boolean;
  updatedAt: string | null;
}
const empty = { data: null, error: null, loading: true, updatedAt: null };

/** Independent resources let the list remain useful if only the summary fails. */
export function useFleetResource<T>(loader: Loader<T>) {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<Resource<T> & { loader: Loader<T> }>({
    ...empty,
    loader,
  });
  useEffect(() => {
    const controller = new AbortController();
    setState((previous) => ({
      ...(previous.loader === loader ? previous : empty),
      loader,
      error: null,
      loading: true,
    }));
    void loader(controller.signal)
      .then((data) => {
        if (!controller.signal.aborted)
          setState({
            data,
            error: null,
            loading: false,
            updatedAt: new Date().toISOString(),
            loader,
          });
      })
      .catch((error) => {
        if (!controller.signal.aborted)
          setState((previous) => ({
            ...previous,
            loading: false,
            error:
              error instanceof FleetApiError
                ? error
                : new FleetApiError(
                    "Unable to load fleet data. Try again.",
                    "UNKNOWN_ERROR",
                  ),
          }));
      });
    return () => controller.abort();
  }, [loader, attempt]);
  // Never label the previous source's data as belonging to a newly selected source.
  return {
    ...(state.loader === loader ? state : empty),
    refresh: () => setAttempt((value) => value + 1),
  };
}
