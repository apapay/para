/*
 * Copyright 2013-2016 Erudika. https://erudika.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For issues and patches go to: https://github.com/erudika
 */
package com.erudika.para.i18n;

import static org.junit.Assert.*;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 * @author Alex Bogdanovski [alex@erudika.com]
 */
@Ignore
public abstract class CurrencyConverterTest {

	protected CurrencyConverter cc;

	@Test
	public void testConvertCurrency() {
		assertTrue(cc.convertCurrency(null, null, null) == 0.0);
		assertTrue(cc.convertCurrency(1, null, null) == 0.0);
		assertTrue(cc.convertCurrency(1, "USD", "USD") == 1.0);
		assertTrue(cc.convertCurrency(1, "EUR", "EUR") == 1.0);
		assertTrue(cc.convertCurrency(1, "EUR", "JPY") > 1.0);
		assertTrue(cc.convertCurrency(-1, "xxx", "xxx") == -1.0);
	}

}