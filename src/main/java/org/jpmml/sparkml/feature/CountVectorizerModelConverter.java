/*
 * Copyright (c) 2017 Villu Ruusmann
 *
 * This file is part of JPMML-SparkML
 *
 * JPMML-SparkML is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPMML-SparkML is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with JPMML-SparkML.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jpmml.sparkml.feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.base.Joiner;
import org.apache.spark.ml.feature.CountVectorizerModel;
import org.dmg.pmml.DataType;
import org.dmg.pmml.DefineFunction;
import org.dmg.pmml.FieldName;
import org.dmg.pmml.FieldRef;
import org.dmg.pmml.OpType;
import org.dmg.pmml.ParameterField;
import org.dmg.pmml.TextIndex;
import org.dmg.pmml.TextIndexNormalization;
import org.jpmml.converter.Feature;
import org.jpmml.converter.PMMLUtil;
import org.jpmml.sparkml.DocumentFeature;
import org.jpmml.sparkml.FeatureConverter;
import org.jpmml.sparkml.SparkMLEncoder;
import org.jpmml.sparkml.TermFeature;
import org.jpmml.sparkml.TermUtil;

public class CountVectorizerModelConverter extends FeatureConverter<CountVectorizerModel> {

	public CountVectorizerModelConverter(CountVectorizerModel transformer){
		super(transformer);
	}

	@Override
	public List<Feature> encodeFeatures(SparkMLEncoder encoder){
		CountVectorizerModel transformer = getTransformer();

		DocumentFeature documentFeature = (DocumentFeature)encoder.getOnlyFeature(transformer.getInputCol());

		ParameterField documentField = new ParameterField(FieldName.create("document"));

		ParameterField termField = new ParameterField(FieldName.create("term"));

		TextIndex textIndex = new TextIndex(documentField.getName())
			.setTokenize(Boolean.TRUE)
			.setWordSeparatorCharacterRE(documentFeature.getWordSeparatorRE())
			.setLocalTermWeights(transformer.getBinary() ? TextIndex.LocalTermWeights.BINARY : null)
			.setExpression(new FieldRef(termField.getName()));

		Set<DocumentFeature.StopWordSet> stopWordSets = documentFeature.getStopWordSets();
		for(DocumentFeature.StopWordSet stopWordSet : stopWordSets){

			if(stopWordSet.isEmpty()){
				continue;
			}

			String tokenRE;

			String wordSeparatorRE = documentFeature.getWordSeparatorRE();
			switch(wordSeparatorRE){
				case "\\s+":
					tokenRE = "(^|\\s+)\\p{Punct}*(" + JOINER.join(stopWordSet) + ")\\p{Punct}*(\\s+|$)";
					break;
				case "\\W+":
					tokenRE = "(\\W+)(" + JOINER.join(stopWordSet) + ")(\\W+)";
					break;
				default:
					throw new IllegalArgumentException("Expected \"\\s+\" or \"\\W+\" as splitter regex pattern, got \"" + wordSeparatorRE + "\"");
			}

			Map<String, List<String>> data = new LinkedHashMap<>();
			data.put("string", Collections.singletonList(tokenRE));
			data.put("stem", Collections.singletonList(" "));
			data.put("regex", Collections.singletonList("true"));

			TextIndexNormalization textIndexNormalization = new TextIndexNormalization()
				.setCaseSensitive(stopWordSet.isCaseSensitive())
				.setRecursive(Boolean.TRUE) // Handles consecutive matches. See http://stackoverflow.com/a/25085385
				.setInlineTable(PMMLUtil.createInlineTable(data));

			textIndex.addTextIndexNormalizations(textIndexNormalization);
		}

		DefineFunction defineFunction = new DefineFunction("tf" + "@" + String.valueOf(CountVectorizerModelConverter.SEQUENCE.getAndIncrement()), OpType.CONTINUOUS, null)
			.setDataType(DataType.INTEGER)
			.addParameterFields(documentField, termField)
			.setExpression(textIndex);

		encoder.addDefineFunction(defineFunction);

		List<Feature> result = new ArrayList<>();

		String[] vocabulary = transformer.vocabulary();
		for(int i = 0; i < vocabulary.length; i++){
			String term = vocabulary[i];

			if(TermUtil.hasPunctuation(term)){
				throw new IllegalArgumentException(term);
			}

			result.add(new TermFeature(encoder, defineFunction, documentFeature, term));
		}

		return result;
	}

	private static final Joiner JOINER = Joiner.on("|");

	private static final AtomicInteger SEQUENCE = new AtomicInteger(1);
}