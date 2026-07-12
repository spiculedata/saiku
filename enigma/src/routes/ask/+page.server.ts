import type { PageServerLoad } from './$types';
import { ossieAskHealth } from '$lib/server/saiku';
import { storeEnabled } from '$lib/server/store';

const SUGGESTIONS = [
	'How many companies are there in each jurisdiction?',
	'What are the most common ways control is held?',
	'Top owner nationalities by number of interests',
	'How many companies are active versus dissolved?'
];

export const load: PageServerLoad = async ({ fetch }) => {
	const health = await ossieAskHealth({ fetch });
	return { health, canSave: storeEnabled(), suggestions: SUGGESTIONS };
};
