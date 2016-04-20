package org.unizar.nutch.scoring.geo;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.apache.nutch.crawl.CrawlDatum;
import org.apache.nutch.crawl.Inlinks;
import org.apache.nutch.indexer.NutchDocument;
import org.apache.nutch.metadata.Nutch;
import org.apache.nutch.parse.Parse;
import org.apache.nutch.parse.ParseData;
import org.apache.nutch.protocol.Content;
import org.apache.nutch.scoring.ScoringFilter;
import org.apache.nutch.scoring.ScoringFilterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unizar.nutch.scoring.geo.thesaurus.Thesaurus;
import org.unizar.nutch.scoring.term.TermFreq;

import uk.ac.shef.dcs.oak.jate.model.Term;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

// Slf4j Logging imports

/**
 * This plugin implements the shark-search algorithm
 * 
 * @author Jorge Cancer
 */
public class SharkScoringFilter implements ScoringFilter {

	private final static Logger LOG = LoggerFactory.getLogger(SharkScoringFilter.class);

	private final static String ANCHOR_CONTEXT = "anchor_context";
	private static final Text TEXT_ANCHOR_CONTEXT = new Text(ANCHOR_CONTEXT);

	private final static String ANCHOR = "anchor";
	private static final Text TEXT_ANCHOR = new Text(ANCHOR);

	private final static String TEXT = "text";

	private float delta;

	private float beta;

	private float gamma;

	private Configuration conf;
	private float scoreInjected;
	private float scorePower;
	private Thesaurus th;

	public Configuration getConf() {
		return conf;
	}

	public void setConf(Configuration conf) {
		this.conf = conf;
		delta = conf.getFloat("score.geo.delta", 0.5f);
		beta = conf.getFloat("score.geo.beta", 0.5f);
		gamma = conf.getFloat("score.geo.gamma", 0.5f);
		th = new Thesaurus();
		/*
		 * scorePower = conf.getFloat("indexer.score.power", 0.5f);
		 * internalScoreFactor = conf.getFloat("db.score.link.internal", 1.0f);
		 * externalScoreFactor = conf.getFloat("db.score.link.external", 1.0f);
		 * countFiltered = conf.getBoolean("db.score.count.filtered", false);
		 */
	}

	public void injectedScore(Text url, CrawlDatum datum) throws ScoringFilterException {

	}

	/**
	 * Set an initial score for newly discovered pages. Note: newly discovered
	 * pages have at least one inlink with its score contribution, so filter
	 * implementations may choose to set initial score to zero (unknown value),
	 * and then the inlink score contribution will set the "real" value of the
	 * new page.
	 * 
	 * @param url
	 *            url of the page
	 * @param datum
	 *            new datum. Filters will modify it in-place.
	 * @throws ScoringFilterException
	 */
	public void initialScore(Text url, CrawlDatum datum) throws ScoringFilterException {
		datum.setScore(0.0f);
	}

	/**
	 * 
	 */
	public float generatorSortValue(Text url, CrawlDatum datum, float initSort) throws ScoringFilterException {
		return datum.getScore() * initSort;
	}

	/**
	 * 
	 */
	public void passScoreBeforeParsing(Text url, CrawlDatum datum, Content content) {
		content.getMetadata().set(Nutch.SCORE_KEY, "" + datum.getScore());
	}

	/**
	 * Copy the value from Content metadata under Fetcher.SCORE_KEY to
	 * parseData.
	 */
	public void passScoreAfterParsing(Text url, Content content, Parse parse) {

		// TODO acaba esto | texto guardado en metadatos para acceder desde el
		// metodo de asignar score a outlinks
		parse.getData().getContentMeta().set(TEXT, parse.getText());

		parse.getData().getContentMeta().set(Nutch.SCORE_KEY, content.getMetadata().get(Nutch.SCORE_KEY));
	}

	/** Increase the score by a sum of inlinked scores. */
	public void updateDbScore(Text url, CrawlDatum old, CrawlDatum datum, List<CrawlDatum> inlinked)
			throws ScoringFilterException {
		float adjust = 0.0f;
		for (CrawlDatum linked : inlinked) {
			adjust += linked.getScore();
		}
		if (old == null)
			old = datum;
		datum.setScore(old.getScore() + adjust);
	}

	/**
	 *
	 */
	public CrawlDatum distributeScoreToOutlinks(Text fromUrl, ParseData parseData,
			Collection<Entry<Text, CrawlDatum>> targets, CrawlDatum adjust, int allCount)
					throws ScoringFilterException {

		/*
		 * Get the inherited score
		 */
		float inheritedScore = scoreInjected; // Default value
		String scoreString = parseData.getContentMeta().get(Nutch.SCORE_KEY);
		if (scoreString != null) {
			try {
				inheritedScore = Float.parseFloat(scoreString);
			} catch (Exception e) {
				LOG.error("Error: ", e);
			}
		}

		/*
		 * Computing the inherited score of child node
		 */
		float scoreChild = delta * (relevance(parseData) > 0 ? relevance(parseData) : inheritedScore);

		for (Entry<Text, CrawlDatum> entry : targets) {
			float potentialScore = computePotentialScore(scoreChild, entry.getValue().getMetaData());
			entry.getValue().setScore(potentialScore);
		}
		return null;
	}

	private float computePotentialScore(float scoreChild, Map metadata) {
		String anchor = metadata.get(TEXT_ANCHOR).toString();
		String context = metadata.get(TEXT_ANCHOR_CONTEXT).toString();
		float anchorScore = relevanceText(anchor);
		float anchorContextScore = anchorScore > 0 ? 1f : relevanceText(context);
		float neighbourhoodScore = (beta * anchorScore) + ((1 - beta) * anchorContextScore);
		// Saving the score
		return (gamma * scoreChild) + ((1 - gamma) * neighbourhoodScore);
	}

	private float relevanceText(String text) {
		String[] words = text.split(" ");
		float rel = 0.0f;
		for (String word : words) {
			int pow = th.execQuery(word);
			rel += pow;
		}
		return rel;
	}

	private float relevance(ParseData parseData) {
		String content = parseData.getContentMeta().get(TEXT);
		Term[] terms = TermFreq.getTerms(content);
		float rel = 0.0f;
		if (terms != null) {
			for (Term term : terms) {
				// if (term.getConfidence() > 1) {
				int pow = th.execQuery(term.getConcept());
				rel += term.getConfidence() * pow;
				// }
			}
		}
		return rel;
	}

	/** Dampen the boost value by scorePower. */
	public float indexerScore(Text url, NutchDocument doc, CrawlDatum dbDatum, CrawlDatum fetchDatum, Parse parse,
			Inlinks inlinks, float initScore) throws ScoringFilterException {
		return (float) Math.pow(dbDatum.getScore(), scorePower) * initScore;
	}
}
