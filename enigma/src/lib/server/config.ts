import { env } from '$env/dynamic/private';

export const config = {
	benafideApi: env.BENAFIDE_API ?? 'http://lineage-prod:8000',
	saikuApi: env.SAIKU_API ?? 'http://localhost:8080',
	saikuUser: env.SAIKU_USER ?? 'admin',
	saikuPass: env.SAIKU_PASS ?? 'admin'
};
