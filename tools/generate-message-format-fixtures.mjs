// The generator evaluates only the exact pinned upstream parser; normal tests use JSON.
import fs from 'node:fs';
import crypto from 'node:crypto';

const [reference, target] = process.argv.slice(2);
if (!reference || !target) throw new Error('Usage: node tools/generate-message-format-fixtures.mjs <reference-file> <new-output-file>');
const raw = fs.readFileSync(reference);
const blob = crypto.createHash('sha1').update(`blob ${raw.length}\0`).update(raw).digest('hex');
if (blob !== '25f07f89a8dfabc9047e77a4b82281575e883a3c') throw new Error('Reference does not match pinned ChitChat parser');
const {parseInline, parseMessage} = await import(`data:text/javascript;base64,${raw.toString('base64')}`);
const cases = [];
const add = (name, source) => cases.push({name, source, inline: parseInline(source), blocks: parseMessage(source)});
const manual=[
 ['empty',''],['ordinary','just chatting'],['math','2 * 3 * 4 = 24'],['snake','snake_case_names and file_name.txt'],
 ['unmatched','a * lonely star and an _ underscore'],['two-stars','**'],['space-markers','price: $5 * before * tax'],
 ['email','mail me at someone@example.org'],['unicode','emoji 🎉 and ümlauts'],['blank-lines','\n\n'],['trailing-newline','a\n'],
 ['strong','so *very* nice'],['double-strong','**also** bold'],['emphasis','_quietly_ said'],['code','run `npm *test*` now'],
 ['nested','*bold _and italic_*'],['unclosed','*not closed'],['space-close','* spaced *'],['newline-inline','*across\nlines*'],
 ['empty-code','``'],['nested-code','*bold `literal _code_`*'],['adjacent-markers','*one**two* _three_'],
 ['escaped','\\*literal* \\_under_ \\`tick`'],['shrug','¯\\_(ツ)_/¯'],['double-backslash','\\\\_still literal_'],
 ['mention','hi @some_user_name!'],['mention-em','_hi @bob_'],['short-mention','@a_b @ab _word_'],
 ['long-mention','@'+('a'.repeat(32))+'_tail_'],['mention-dash','@one.two-three_4'],
 ['bmp-word','é*word* 中_word_ 2²*word* Ⅳ*word*'],['supplementary-word','𐐀*word*'],['emoji-boundary','🎉_word_'],
 ['cr-emphasis','*a\rb*'],['line-separator-emphasis','*a\u2028b*'],['nbsp','*\u00a0word*'],['bom','\ufeff*word*'],
 ['nel','*a\u0085b*'],['no-markdown','- list\n# heading\n~~strike~~\n[not a named link]'],
 ['url','see https://example.org/a_b?c=1.'],['url-parentheses','(https://en.wikipedia.org/wiki/Tea_(drink))'],
 ['url-case','HTTPS://Example.org'],['url-punctuation','https://example.org/x))...!? end'],
 ['url-port-path','https://EXAMPLE.org:443/a/../b?q=%2f#x'],['url-idn','https://bücher.example/雪?q=ü'],
 ['url-http','http://example.org'],['url-local','https://localhost:8443/path'],['url-ipv6','https://[::1]:8443/x'],
 ['url-empty-userinfo','https://@example.org'],['url-short','http://a'],['url-word','xhttps://example.org'],
 ['url-protocols','javascript:alert(1) data:text/html,hi ftp://example.org https://'],
 ['url-style','*https://example.org/a_b*'],['url-code','`https://example.org/_word_`'],
 ['url-angle','<https://example.org/a>'],['html','<img src="x" onerror="alert(1)"> &amp; <b>text</b>'],
 ['quote','> wise words\n> *twice*\nreply'],['quote-empty','>\n> \nreply'],['quote-leading',' > unchanged'],
 ['quote-repeat','>> nested-looking'],['quote-cr','>a\r'],['quote-ls','>a\u2028'],['quote-cr-middle','>a\rb'],
 ['fence','look:\n```\nif (a) {\n  *b*\n}\n```\ndone'],['fence-unclosed','```\nnot closed'],
 ['fence-language','```js  \nbody\n  ```  '],['fence-leading',' ```\nbody\n```'],['fence-extra','````\nbody\n```'],
 ['fence-empty','```\n```'],['fence-inner','```\n```suffix\nbody\n```'],['fence-crlf','```\r\nbody\r\n```\r'],
 ['fence-whitespace','```\u00a0\nbody\n\ufeff```\u3000'],['blocks','one\n> two\n```code\n_three_\n```\nfour'],
];
for(const [name,source] of manual) add(name,source);
let state=0x69d704;
const alphabet=['a','b','x',' ','*','_','`','@','.',')','(','\\','\n','\r','Ü','²','𐐀'];
for(let n=0;n<64;n++){
 let source='';
 for(let i=0;i<48;i++){state^=state<<13;state^=state>>>17;state^=state<<5;source+=alphabet[(state>>>0)%alphabet.length];}
 add(`seeded-${String(n+1).padStart(3,'0')}`,source);
}
fs.writeFileSync(target, '[\n' + cases.map(row => JSON.stringify(row)).join(',\n') + '\n]\n', {flag:'wx'});
console.log(JSON.stringify({fixtures:cases.length, bytes:fs.statSync(target).size}));
