import type { FleetApiError } from "../api/fleet";

export function ResourceNotice({
  name,
  loading,
  error,
  hasData,
  retry,
}: {
  name: string;
  loading: boolean;
  error: FleetApiError | null;
  hasData: boolean;
  retry: () => void;
}) {
  if (error)
    return (
      <div className="resource-state resource-error" role="alert">
        <h3>Could not load {name}</h3>
        <p>{error.message}</p>
        {hasData && (
          <p>
            <strong>
              Showing the previous snapshot. It may be out of date.
            </strong>
          </p>
        )}
        <button className="action-button" onClick={retry}>
          Retry {name}
        </button>
      </div>
    );
  if (loading)
    return (
      <p className="resource-state" role="status">
        {hasData ? "Refreshing" : "Loading"} {name}…
      </p>
    );
  return null;
}
