
// this file is generated — do not edit it


/// <reference types="@sveltejs/kit" />

/**
 * This module provides access to environment variables that are injected _statically_ into your bundle at build time and are limited to _private_ access.
 * 
 * |         | Runtime                                                                    | Build time                                                               |
 * | ------- | -------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
 * | Private | [`$env/dynamic/private`](https://svelte.dev/docs/kit/$env-dynamic-private) | [`$env/static/private`](https://svelte.dev/docs/kit/$env-static-private) |
 * | Public  | [`$env/dynamic/public`](https://svelte.dev/docs/kit/$env-dynamic-public)   | [`$env/static/public`](https://svelte.dev/docs/kit/$env-static-public)   |
 * 
 * Static environment variables are [loaded by Vite](https://vitejs.dev/guide/env-and-mode.html#env-files) from `.env` files and `process.env` at build time and then statically injected into your bundle at build time, enabling optimisations like dead code elimination.
 * 
 * **_Private_ access:**
 * 
 * - This module cannot be imported into client-side code
 * - This module only includes variables that _do not_ begin with [`config.kit.env.publicPrefix`](https://svelte.dev/docs/kit/configuration#env) _and do_ start with [`config.kit.env.privatePrefix`](https://svelte.dev/docs/kit/configuration#env) (if configured)
 * 
 * For example, given the following build time environment:
 * 
 * ```env
 * ENVIRONMENT=production
 * PUBLIC_BASE_URL=http://site.com
 * ```
 * 
 * With the default `publicPrefix` and `privatePrefix`:
 * 
 * ```ts
 * import { ENVIRONMENT, PUBLIC_BASE_URL } from '$env/static/private';
 * 
 * console.log(ENVIRONMENT); // => "production"
 * console.log(PUBLIC_BASE_URL); // => throws error during build
 * ```
 * 
 * The above values will be the same _even if_ different values for `ENVIRONMENT` or `PUBLIC_BASE_URL` are set at runtime, as they are statically replaced in your code with their build time values.
 */
declare module '$env/static/private' {
	export const NVM_INC: string;
	export const STARSHIP_SHELL: string;
	export const MANPATH: string;
	export const CMUX_BUNDLED_CLI_PATH: string;
	export const NoDefaultCurrentDirectoryInExePath: string;
	export const GHOSTTY_RESOURCES_DIR: string;
	export const CLAUDE_EFFORT: string;
	export const CLAUDE_CODE_ENTRYPOINT: string;
	export const TERM_PROGRAM: string;
	export const CMUX_SHELL_INTEGRATION_DIR: string;
	export const CMUX_CLAUDE_WRAPPER_SHIM_ROOT: string;
	export const NODE: string;
	export const GHOSTTY_SURFACE_ID: string;
	export const CMUX_NO_PR_WATCH: string;
	export const INIT_CWD: string;
	export const NVM_CD_FLAGS: string;
	export const PYENV_ROOT: string;
	export const TERM: string;
	export const SHELL: string;
	export const CMUX_BUNDLE_ID: string;
	export const CLAUDE_PID: string;
	export const CLAUDE_CODE_CHILD_SESSION: string;
	export const TMPDIR: string;
	export const HOMEBREW_REPOSITORY: string;
	export const CMUX_PANEL_ID: string;
	export const npm_config_global_prefix: string;
	export const GRADLE_HOME: string;
	export const PERL5LIB: string;
	export const CMUX_SOCKET: string;
	export const TERM_PROGRAM_VERSION: string;
	export const FPATH: string;
	export const COLOR: string;
	export const PERL_MB_OPT: string;
	export const npm_config_noproxy: string;
	export const SDKMAN_PLATFORM: string;
	export const npm_config_local_prefix: string;
	export const ZSH: string;
	export const GIT_EDITOR: string;
	export const AI_AGENT: string;
	export const NVM_DIR: string;
	export const USER: string;
	export const LS_COLORS: string;
	export const COMMAND_MODE: string;
	export const npm_config_globalconfig: string;
	export const SDKMAN_CANDIDATES_API: string;
	export const SSH_AUTH_SOCK: string;
	export const Q_SET_PARENT_CHECK: string;
	export const CMUX_SUPPRESS_SUBAGENT_NOTIFICATIONS: string;
	export const __CF_USER_TEXT_ENCODING: string;
	export const npm_execpath: string;
	export const VIRTUAL_ENV_DISABLE_PROMPT: string;
	export const PAGER: string;
	export const LSCOLORS: string;
	export const PATH: string;
	export const npm_package_json: string;
	export const _: string;
	export const LaunchInstanceID: string;
	export const GHOSTTY_SHELL_FEATURES: string;
	export const CMUX_PORT: string;
	export const npm_config_userconfig: string;
	export const npm_config_init_module: string;
	export const SHELL_PID: string;
	export const __CFBundleIdentifier: string;
	export const npm_command: string;
	export const TTY: string;
	export const PWD: string;
	export const CMUX_PORT_END: string;
	export const JAVA_HOME: string;
	export const CMUX_NO_GIT_WATCH: string;
	export const npm_lifecycle_event: string;
	export const EDITOR: string;
	export const CMUX_ZSH_RESTORE_TERM: string;
	export const CMUX_WORKSPACE_ID: string;
	export const CMUX_SHELL_INTEGRATION: string;
	export const npm_package_name: string;
	export const LANG: string;
	export const npm_config_npm_version: string;
	export const XPC_FLAGS: string;
	export const CMUX_KIRO_NOTIFICATION_LEVEL: string;
	export const npm_config_node_gyp: string;
	export const CMUX_LOAD_GHOSTTY_ZSH_INTEGRATION: string;
	export const npm_package_version: string;
	export const XPC_SERVICE_NAME: string;
	export const PYENV_SHELL: string;
	export const SHLVL: string;
	export const HOME: string;
	export const CMUX_TAB_ID: string;
	export const TERMINFO: string;
	export const CLAUDE_CODE_EXECPATH: string;
	export const HOMEBREW_PREFIX: string;
	export const CMUX_PORT_RANGE: string;
	export const PERL_LOCAL_LIB_ROOT: string;
	export const npm_config_cache: string;
	export const STARSHIP_SESSION_KEY: string;
	export const LESS: string;
	export const LOGNAME: string;
	export const npm_lifecycle_script: string;
	export const SDKMAN_DIR: string;
	export const XDG_DATA_DIRS: string;
	export const COREPACK_ENABLE_AUTO_PIN: string;
	export const GHOSTTY_BIN_DIR: string;
	export const NVM_BIN: string;
	export const npm_config_user_agent: string;
	export const CLAUDE_CODE_SESSION_ID: string;
	export const SDKMAN_CANDIDATES_DIR: string;
	export const INFOPATH: string;
	export const HOMEBREW_CELLAR: string;
	export const CMUX_SOCKET_PATH: string;
	export const Q_TERM: string;
	export const QTERM_SESSION_ID: string;
	export const OSLogRateLimit: string;
	export const CMUX_CLAUDE_WRAPPER_SHIM: string;
	export const CLAUDECODE: string;
	export const SECURITYSESSIONID: string;
	export const CMUX_SURFACE_ID: string;
	export const PERL_MM_OPT: string;
	export const npm_node_execpath: string;
	export const npm_config_prefix: string;
	export const COLORTERM: string;
	export const TEST: string;
	export const VITEST: string;
	export const NODE_ENV: string;
	export const PROD: string;
	export const DEV: string;
	export const BASE_URL: string;
	export const MODE: string;
}

/**
 * This module provides access to environment variables that are injected _statically_ into your bundle at build time and are _publicly_ accessible.
 * 
 * |         | Runtime                                                                    | Build time                                                               |
 * | ------- | -------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
 * | Private | [`$env/dynamic/private`](https://svelte.dev/docs/kit/$env-dynamic-private) | [`$env/static/private`](https://svelte.dev/docs/kit/$env-static-private) |
 * | Public  | [`$env/dynamic/public`](https://svelte.dev/docs/kit/$env-dynamic-public)   | [`$env/static/public`](https://svelte.dev/docs/kit/$env-static-public)   |
 * 
 * Static environment variables are [loaded by Vite](https://vitejs.dev/guide/env-and-mode.html#env-files) from `.env` files and `process.env` at build time and then statically injected into your bundle at build time, enabling optimisations like dead code elimination.
 * 
 * **_Public_ access:**
 * 
 * - This module _can_ be imported into client-side code
 * - **Only** variables that begin with [`config.kit.env.publicPrefix`](https://svelte.dev/docs/kit/configuration#env) (which defaults to `PUBLIC_`) are included
 * 
 * For example, given the following build time environment:
 * 
 * ```env
 * ENVIRONMENT=production
 * PUBLIC_BASE_URL=http://site.com
 * ```
 * 
 * With the default `publicPrefix` and `privatePrefix`:
 * 
 * ```ts
 * import { ENVIRONMENT, PUBLIC_BASE_URL } from '$env/static/public';
 * 
 * console.log(ENVIRONMENT); // => throws error during build
 * console.log(PUBLIC_BASE_URL); // => "http://site.com"
 * ```
 * 
 * The above values will be the same _even if_ different values for `ENVIRONMENT` or `PUBLIC_BASE_URL` are set at runtime, as they are statically replaced in your code with their build time values.
 */
declare module '$env/static/public' {
	
}

/**
 * This module provides access to environment variables set _dynamically_ at runtime and that are limited to _private_ access.
 * 
 * |         | Runtime                                                                    | Build time                                                               |
 * | ------- | -------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
 * | Private | [`$env/dynamic/private`](https://svelte.dev/docs/kit/$env-dynamic-private) | [`$env/static/private`](https://svelte.dev/docs/kit/$env-static-private) |
 * | Public  | [`$env/dynamic/public`](https://svelte.dev/docs/kit/$env-dynamic-public)   | [`$env/static/public`](https://svelte.dev/docs/kit/$env-static-public)   |
 * 
 * Dynamic environment variables are defined by the platform you're running on. For example if you're using [`adapter-node`](https://github.com/sveltejs/kit/tree/main/packages/adapter-node) (or running [`vite preview`](https://svelte.dev/docs/kit/cli)), this is equivalent to `process.env`.
 * 
 * **_Private_ access:**
 * 
 * - This module cannot be imported into client-side code
 * - This module includes variables that _do not_ begin with [`config.kit.env.publicPrefix`](https://svelte.dev/docs/kit/configuration#env) _and do_ start with [`config.kit.env.privatePrefix`](https://svelte.dev/docs/kit/configuration#env) (if configured)
 * 
 * > [!NOTE] In `dev`, `$env/dynamic` includes environment variables from `.env`. In `prod`, this behavior will depend on your adapter.
 * 
 * > [!NOTE] To get correct types, environment variables referenced in your code should be declared (for example in an `.env` file), even if they don't have a value until the app is deployed:
 * >
 * > ```env
 * > MY_FEATURE_FLAG=
 * > ```
 * >
 * > You can override `.env` values from the command line like so:
 * >
 * > ```sh
 * > MY_FEATURE_FLAG="enabled" npm run dev
 * > ```
 * 
 * For example, given the following runtime environment:
 * 
 * ```env
 * ENVIRONMENT=production
 * PUBLIC_BASE_URL=http://site.com
 * ```
 * 
 * With the default `publicPrefix` and `privatePrefix`:
 * 
 * ```ts
 * import { env } from '$env/dynamic/private';
 * 
 * console.log(env.ENVIRONMENT); // => "production"
 * console.log(env.PUBLIC_BASE_URL); // => undefined
 * ```
 */
declare module '$env/dynamic/private' {
	export const env: {
		NVM_INC: string;
		STARSHIP_SHELL: string;
		MANPATH: string;
		CMUX_BUNDLED_CLI_PATH: string;
		NoDefaultCurrentDirectoryInExePath: string;
		GHOSTTY_RESOURCES_DIR: string;
		CLAUDE_EFFORT: string;
		CLAUDE_CODE_ENTRYPOINT: string;
		TERM_PROGRAM: string;
		CMUX_SHELL_INTEGRATION_DIR: string;
		CMUX_CLAUDE_WRAPPER_SHIM_ROOT: string;
		NODE: string;
		GHOSTTY_SURFACE_ID: string;
		CMUX_NO_PR_WATCH: string;
		INIT_CWD: string;
		NVM_CD_FLAGS: string;
		PYENV_ROOT: string;
		TERM: string;
		SHELL: string;
		CMUX_BUNDLE_ID: string;
		CLAUDE_PID: string;
		CLAUDE_CODE_CHILD_SESSION: string;
		TMPDIR: string;
		HOMEBREW_REPOSITORY: string;
		CMUX_PANEL_ID: string;
		npm_config_global_prefix: string;
		GRADLE_HOME: string;
		PERL5LIB: string;
		CMUX_SOCKET: string;
		TERM_PROGRAM_VERSION: string;
		FPATH: string;
		COLOR: string;
		PERL_MB_OPT: string;
		npm_config_noproxy: string;
		SDKMAN_PLATFORM: string;
		npm_config_local_prefix: string;
		ZSH: string;
		GIT_EDITOR: string;
		AI_AGENT: string;
		NVM_DIR: string;
		USER: string;
		LS_COLORS: string;
		COMMAND_MODE: string;
		npm_config_globalconfig: string;
		SDKMAN_CANDIDATES_API: string;
		SSH_AUTH_SOCK: string;
		Q_SET_PARENT_CHECK: string;
		CMUX_SUPPRESS_SUBAGENT_NOTIFICATIONS: string;
		__CF_USER_TEXT_ENCODING: string;
		npm_execpath: string;
		VIRTUAL_ENV_DISABLE_PROMPT: string;
		PAGER: string;
		LSCOLORS: string;
		PATH: string;
		npm_package_json: string;
		_: string;
		LaunchInstanceID: string;
		GHOSTTY_SHELL_FEATURES: string;
		CMUX_PORT: string;
		npm_config_userconfig: string;
		npm_config_init_module: string;
		SHELL_PID: string;
		__CFBundleIdentifier: string;
		npm_command: string;
		TTY: string;
		PWD: string;
		CMUX_PORT_END: string;
		JAVA_HOME: string;
		CMUX_NO_GIT_WATCH: string;
		npm_lifecycle_event: string;
		EDITOR: string;
		CMUX_ZSH_RESTORE_TERM: string;
		CMUX_WORKSPACE_ID: string;
		CMUX_SHELL_INTEGRATION: string;
		npm_package_name: string;
		LANG: string;
		npm_config_npm_version: string;
		XPC_FLAGS: string;
		CMUX_KIRO_NOTIFICATION_LEVEL: string;
		npm_config_node_gyp: string;
		CMUX_LOAD_GHOSTTY_ZSH_INTEGRATION: string;
		npm_package_version: string;
		XPC_SERVICE_NAME: string;
		PYENV_SHELL: string;
		SHLVL: string;
		HOME: string;
		CMUX_TAB_ID: string;
		TERMINFO: string;
		CLAUDE_CODE_EXECPATH: string;
		HOMEBREW_PREFIX: string;
		CMUX_PORT_RANGE: string;
		PERL_LOCAL_LIB_ROOT: string;
		npm_config_cache: string;
		STARSHIP_SESSION_KEY: string;
		LESS: string;
		LOGNAME: string;
		npm_lifecycle_script: string;
		SDKMAN_DIR: string;
		XDG_DATA_DIRS: string;
		COREPACK_ENABLE_AUTO_PIN: string;
		GHOSTTY_BIN_DIR: string;
		NVM_BIN: string;
		npm_config_user_agent: string;
		CLAUDE_CODE_SESSION_ID: string;
		SDKMAN_CANDIDATES_DIR: string;
		INFOPATH: string;
		HOMEBREW_CELLAR: string;
		CMUX_SOCKET_PATH: string;
		Q_TERM: string;
		QTERM_SESSION_ID: string;
		OSLogRateLimit: string;
		CMUX_CLAUDE_WRAPPER_SHIM: string;
		CLAUDECODE: string;
		SECURITYSESSIONID: string;
		CMUX_SURFACE_ID: string;
		PERL_MM_OPT: string;
		npm_node_execpath: string;
		npm_config_prefix: string;
		COLORTERM: string;
		TEST: string;
		VITEST: string;
		NODE_ENV: string;
		PROD: string;
		DEV: string;
		BASE_URL: string;
		MODE: string;
		[key: `PUBLIC_${string}`]: undefined;
		[key: `${string}`]: string | undefined;
	}
}

/**
 * This module provides access to environment variables set _dynamically_ at runtime and that are _publicly_ accessible.
 * 
 * |         | Runtime                                                                    | Build time                                                               |
 * | ------- | -------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
 * | Private | [`$env/dynamic/private`](https://svelte.dev/docs/kit/$env-dynamic-private) | [`$env/static/private`](https://svelte.dev/docs/kit/$env-static-private) |
 * | Public  | [`$env/dynamic/public`](https://svelte.dev/docs/kit/$env-dynamic-public)   | [`$env/static/public`](https://svelte.dev/docs/kit/$env-static-public)   |
 * 
 * Dynamic environment variables are defined by the platform you're running on. For example if you're using [`adapter-node`](https://github.com/sveltejs/kit/tree/main/packages/adapter-node) (or running [`vite preview`](https://svelte.dev/docs/kit/cli)), this is equivalent to `process.env`.
 * 
 * **_Public_ access:**
 * 
 * - This module _can_ be imported into client-side code
 * - **Only** variables that begin with [`config.kit.env.publicPrefix`](https://svelte.dev/docs/kit/configuration#env) (which defaults to `PUBLIC_`) are included
 * 
 * > [!NOTE] In `dev`, `$env/dynamic` includes environment variables from `.env`. In `prod`, this behavior will depend on your adapter.
 * 
 * > [!NOTE] To get correct types, environment variables referenced in your code should be declared (for example in an `.env` file), even if they don't have a value until the app is deployed:
 * >
 * > ```env
 * > MY_FEATURE_FLAG=
 * > ```
 * >
 * > You can override `.env` values from the command line like so:
 * >
 * > ```sh
 * > MY_FEATURE_FLAG="enabled" npm run dev
 * > ```
 * 
 * For example, given the following runtime environment:
 * 
 * ```env
 * ENVIRONMENT=production
 * PUBLIC_BASE_URL=http://example.com
 * ```
 * 
 * With the default `publicPrefix` and `privatePrefix`:
 * 
 * ```ts
 * import { env } from '$env/dynamic/public';
 * console.log(env.ENVIRONMENT); // => undefined, not public
 * console.log(env.PUBLIC_BASE_URL); // => "http://example.com"
 * ```
 * 
 * ```
 * 
 * ```
 */
declare module '$env/dynamic/public' {
	export const env: {
		[key: `PUBLIC_${string}`]: string | undefined;
	}
}
