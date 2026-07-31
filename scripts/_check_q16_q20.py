import json
data = json.load(open(r'c:\Apps\Mynoo\scripts\assessment.json', encoding='utf-8'))
for q in data['questions']:
    if q.get('id') in ('q16', 'q20'):
        print(f"=== {q['id']} ===")
        print(repr(q.get('asy', '')))
        print()
