package org.antlr.codebuff;

import com.google.common.base.CharMatcher;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.WritableToken;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.List;
import java.util.Map;
import java.util.Vector;

import static org.antlr.codebuff.CollectFeatures.CAT_ALIGN_WITH_ANCESTOR_CHILD;
import static org.antlr.codebuff.CollectFeatures.CAT_INDENT;
import static org.antlr.codebuff.CollectFeatures.CAT_INDENT_FROM_ANCESTOR_FIRST_TOKEN;
import static org.antlr.codebuff.CollectFeatures.CAT_INJECT_NL;
import static org.antlr.codebuff.CollectFeatures.CAT_INJECT_WS;
import static org.antlr.codebuff.CollectFeatures.FEATURES_ALIGN;
import static org.antlr.codebuff.CollectFeatures.FEATURES_INJECT_WS;
import static org.antlr.codebuff.CollectFeatures.INDEX_FIRST_ON_LINE;
import static org.antlr.codebuff.CollectFeatures.INDEX_PREV_END_COLUMN;
import static org.antlr.codebuff.CollectFeatures.MAX_CONTEXT_DIFF_THRESHOLD;
import static org.antlr.codebuff.CollectFeatures.earliestAncestorStartingWithToken;
import static org.antlr.codebuff.CollectFeatures.getNodeFeatures;
import static org.antlr.codebuff.CollectFeatures.getRealTokens;
import static org.antlr.codebuff.CollectFeatures.getTokensOnPreviousLine;
import static org.antlr.codebuff.CollectFeatures.indexTree;

public class Formatter {
	public static final int INDENT_LEVEL = 4;

	protected final Corpus corpus;
	protected StringBuilder output = new StringBuilder();
	protected InputDocument doc;
	protected ParserRuleContext root;
	protected CommonTokenStream tokens; // track stream so we can examine previous tokens
	protected List<CommonToken> originalTokens; // copy of tokens with line/col info
	protected List<Token> realTokens;           // just the real tokens from tokens

	protected Map<Token, TerminalNode> tokenToNodeMap = null;

	protected Vector<TokenPositionAnalysis> analysis = new Vector<>();

	protected CodekNNClassifier nlwsClassifier;
	protected CodekNNClassifier wsClassifier;
	protected CodekNNClassifier alignClassifier;
	protected int k;

	protected int line = 1;
	protected int charPosInLine = 0;

	protected int tabSize;

	protected boolean debug_NL = true;
	protected int misclassified_NL = 0;
	protected int misclassified_WS = 0;

	public Formatter(Corpus corpus, InputDocument doc, int tabSize) {
		this.corpus = corpus;
		this.doc = doc;
		this.root = doc.tree;
		this.tokens = doc.tokens;
		this.originalTokens = Tool.copy(tokens);
		Tool.wipeLineAndPositionInfo(tokens);
		nlwsClassifier = new CodekNNClassifier(corpus, FEATURES_INJECT_WS);
		alignClassifier = new CodekNNClassifier(corpus, FEATURES_ALIGN);
//		k = (int)Math.sqrt(corpus.X.size());
//		k = 7;
		k = 11;
		this.tabSize = tabSize;
	}

	public String getOutput() {
		return output.toString();
	}

	public List<TokenPositionAnalysis> getAnalysisPerToken() {
		return analysis;
	}


	public String format() {
		if ( tokenToNodeMap == null ) {
			tokenToNodeMap = indexTree(root);
		}

		tokens.seek(0);
		WritableToken firstToken = (WritableToken)tokens.LT(1);
		WritableToken secondToken = (WritableToken)tokens.LT(2);
		// all tokens are wiped of line/col info so set them for first 2
		firstToken.setLine(1);
		firstToken.setCharPositionInLine(0);
		secondToken.setLine(1);
		secondToken.setCharPositionInLine(firstToken.getText().length());

		String prefix = tokens.getText(Interval.of(0, secondToken.getTokenIndex()));
		output.append(prefix);


		realTokens = getRealTokens(tokens);
		for (int i = 2; i<realTokens.size(); i++) { // can't process first 2 tokens
			int tokenIndexInStream = realTokens.get(i).getTokenIndex();
			processToken(i, tokenIndexInStream);
		}
		return output.toString();
	}

	public void processToken(int indexIntoRealTokens, int tokenIndexInStream) {
		CommonToken curToken = (CommonToken)tokens.get(tokenIndexInStream);
		String tokText = curToken.getText();

		emitCommentsToTheLeft(tokenIndexInStream);

		int[] features = getNodeFeatures(tokenToNodeMap, doc, tokenIndexInStream, line, tabSize);
		// must set "prev end column" value as token stream doesn't have it;
		// we're tracking it as we emit tokens
		features[INDEX_PREV_END_COLUMN] = charPosInLine;

		int injectNL_WS = nlwsClassifier.classify(k, features, corpus.injectWhitespace, MAX_CONTEXT_DIFF_THRESHOLD);
		int newlines = 0;
		int ws = 0;
		if ( (injectNL_WS&0xFF)==CAT_INJECT_NL ) {
			newlines = CollectFeatures.unnlcat(injectNL_WS);
		}
		else if ( (injectNL_WS&0xFF)==CAT_INJECT_WS ) {
			ws = CollectFeatures.unwscat(injectNL_WS);
		}

		// getNodeFeatures() also doesn't know what line curToken is on. If \n, we need to find exemplars that start a line
		features[INDEX_FIRST_ON_LINE] = newlines; // use \n prediction to match exemplars for alignment

		int align = alignClassifier.classify(k, features, corpus.align, MAX_CONTEXT_DIFF_THRESHOLD);

		TokenPositionAnalysis tokenPositionAnalysis =
			getTokenAnalysis(features, indexIntoRealTokens, tokenIndexInStream, newlines, align, ws);
		analysis.setSize(tokenIndexInStream+1);
		analysis.set(tokenIndexInStream, tokenPositionAnalysis);

		if ( ws==0 && cannotJoin(realTokens.get(indexIntoRealTokens-1), curToken) ) { // failsafe!
			ws = 1;
		}

		if ( newlines>0 ) {
			output.append(Tool.newlines(newlines));
			line++;
			charPosInLine = 0;

			List<Token> tokensOnPreviousLine = getTokensOnPreviousLine(tokens, tokenIndexInStream, line);
			Token firstTokenOnPrevLine = null;
			if ( tokensOnPreviousLine.size()>0 ) {
				firstTokenOnPrevLine = tokensOnPreviousLine.get(0);
			}

			TerminalNode node = tokenToNodeMap.get(curToken);
			ParserRuleContext parent = (ParserRuleContext)node.getParent();

			if ( align==CAT_INDENT ) {
				if ( firstTokenOnPrevLine!=null ) { // if not on first line, we cannot indent
					int indentedCol = firstTokenOnPrevLine.getCharPositionInLine()+INDENT_LEVEL;
					charPosInLine = indentedCol;
					output.append(Tool.spaces(indentedCol));
				}
			}
			else if ( (align&0xFF)==CAT_ALIGN_WITH_ANCESTOR_CHILD ) {
				int[] deltaChild = CollectFeatures.unaligncat(align);
				int deltaFromAncestor = deltaChild[0];
				int childIndex = deltaChild[1];
				ParserRuleContext earliestLeftAncestor = earliestAncestorStartingWithToken(parent, curToken);
				if ( earliestLeftAncestor==null ) {
					earliestLeftAncestor = parent;
				}
				ParserRuleContext ancestor = CollectFeatures.getAncestor(earliestLeftAncestor, deltaFromAncestor);
				ParseTree child = ancestor.getChild(childIndex);
				Token start = null;
				if ( child instanceof ParserRuleContext ) {
					start = ((ParserRuleContext) child).getStart();
				}
				else if ( child instanceof TerminalNode ){
					start = ((TerminalNode)child).getSymbol();
				}
				else {
					// uh oh.
					System.err.println("Whoops. Tried access invalid child");
				}
				if ( start!=null ) {
					int indentCol = start.getCharPositionInLine();
					charPosInLine = indentCol;
					output.append(Tool.spaces(indentCol));
				}
			}
			else if ( (align&0xFF)==CAT_INDENT_FROM_ANCESTOR_FIRST_TOKEN ) {
				int deltaFromAncestor = CollectFeatures.unindentcat(align);
				ParserRuleContext earliestLeftAncestor = earliestAncestorStartingWithToken(parent, curToken);
				if ( earliestLeftAncestor==null ) {
					earliestLeftAncestor = parent;
				}
				ParserRuleContext ancestor = CollectFeatures.getAncestor(earliestLeftAncestor, deltaFromAncestor);
				Token start = ancestor.getStart();
				int indentCol = start.getCharPositionInLine() + INDENT_LEVEL;
				charPosInLine = indentCol;
				output.append(Tool.spaces(indentCol));
			}
		}
		else {
			// inject whitespace instead of \n?
			output.append(Tool.spaces(ws));
			charPosInLine += ws;
		}

		// update Token object with position information now that we are about
		// to emit it.
		curToken.setLine(line);
		curToken.setCharPositionInLine(charPosInLine);

		// emit
		int n = tokText.length();
		tokenPositionAnalysis.charIndexStart = output.length();
		tokenPositionAnalysis.charIndexStop = tokenPositionAnalysis.charIndexStart + n - 1;
		output.append(tokText);
		charPosInLine += n;
	}

	/** Look into the token stream to get the comments to the left of current
	 *  token. Emit all whitespace and comments except for whitespace at the
	 *  end as we'll inject that per newline prediction.
	 */
	public void emitCommentsToTheLeft(int tokenIndexInStream) {
		List<Token> hiddenTokensToLeft = tokens.getHiddenTokensToLeft(tokenIndexInStream);
		if ( hiddenTokensToLeft!=null ) {
			// if at least one is not whitespace, assume it's a comment and print all hidden stuff including whitespace
			boolean hasComment = false;
			for (Token hidden : hiddenTokensToLeft) {
				String hiddenText = hidden.getText();
				if ( !hiddenText.matches("\\s+") ) {
					hasComment = true;
					break;
				}
			}
			if ( hasComment ) {
				// avoid whitespace at end of sequence as we'll inject that
				int last = -1;
				for (int i=hiddenTokensToLeft.size()-1; i>=0; i--) {
					Token hidden = hiddenTokensToLeft.get(i);
					String hiddenText = hidden.getText();
					if ( !hiddenText.matches("\\s+") ) {
						last = i;
						break;
					}
				}
				List<Token> stripped = hiddenTokensToLeft.subList(0, last+1);
				for (Token hidden : stripped) {
					String hiddenText = hidden.getText();
					output.append(hiddenText);
					if ( hiddenText.matches("\\n+") ) {
						line += CharMatcher.is('\n').countIn(hiddenText);
						charPosInLine = 0;
					}
					else {
						// if a comment or plain ' ', must count char position
						charPosInLine += hiddenText.length();
					}
				}
			}
		}
	}

	public TokenPositionAnalysis getTokenAnalysis(int[] features, int indexIntoRealTokens, int tokenIndexInStream,
	                                              int injectNewline,
	                                              int alignWithPrevious,
	                                              int ws)
	{
		CommonToken curToken = (CommonToken)tokens.get(tokenIndexInStream);
		// compare prediction of newline against original, alert about any diffs
		CommonToken prevToken = originalTokens.get(curToken.getTokenIndex()-1);
		CommonToken originalCurToken = originalTokens.get(curToken.getTokenIndex());

		boolean failsafeTriggered = false;
		if ( ws==0 && cannotJoin(realTokens.get(indexIntoRealTokens-1), curToken) ) { // failsafe!
			ws = 1;
			failsafeTriggered = true;
		}

		boolean prevIsWS = prevToken.getChannel()==Token.HIDDEN_CHANNEL; // assume this means whitespace
		int actualNL = Tool.count(prevToken.getText(), '\n');
		int actualWS = Tool.count(prevToken.getText(), ' ');
		String newlinePredictionString = String.format("### line %d: predicted %d \\n actual %s",
		                                               originalCurToken.getLine(), injectNewline, prevIsWS ? actualNL : "none");
		String alignPredictionString = String.format("### line %d: predicted %s actual %s",
		                                             originalCurToken.getLine(),
		                                             alignWithPrevious==1?"align":"unaligned",
		                                             "?");

		String newlineAnalysis = newlinePredictionString+"\n"+
			nlwsClassifier.getPredictionAnalysis(doc, k, features, corpus.injectWhitespace,
			                                     MAX_CONTEXT_DIFF_THRESHOLD);
		String alignAnalysis =alignPredictionString+"\n"+
			alignClassifier.getPredictionAnalysis(doc, k, features, corpus.align,
			                                      MAX_CONTEXT_DIFF_THRESHOLD);
		return new TokenPositionAnalysis(newlineAnalysis, alignAnalysis, "n/a");
	}

	/** Do not join two words like "finaldouble" or numbers like "3double",
	 *  "double3", "34", (3 and 4 are different tokens) etc...
	 */
	public static boolean cannotJoin(Token prevToken, Token curToken) {
		String prevTokenText = prevToken.getText();
		char prevLastChar = prevTokenText.charAt(prevTokenText.length()-1);
		String curTokenText = curToken.getText();
		char curFirstChar = curTokenText.charAt(0);
		return Character.isLetterOrDigit(prevLastChar) && Character.isLetterOrDigit(curFirstChar);
	}
}
