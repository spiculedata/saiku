<script lang="ts">
  // Lightweight stand-in for RepositoryBrowser used by SaveQueryModal
  // tests. Renders a single marked element so DOM-order assertions can
  // locate "the folder browser" without pulling in the real component's
  // repository store / fetch machinery.
  interface Props {
    mode?: "load" | "save";
    fileTypes?: string[];
    selectedPath?: string;
    onSelect?: (path: string) => void;
    onOpen?: (path: string) => void;
  }
  let { selectedPath = "" }: Props = $props();
</script>

<div data-testid="repo-browser">{selectedPath}</div>
