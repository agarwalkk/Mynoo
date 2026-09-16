import firebase_admin
from firebase_admin import credentials, firestore
import json
import sys
sys.stdout.reconfigure(encoding='utf-8')

cred = credentials.Certificate('mynoo-1e880-serviceaccount.json')
try:
    firebase_admin.initialize_app(cred)
except:
    pass

db = firestore.client()
doc = db.collection('kids').document('Anish').collection('assessments').document('havqjOtjo3FZgS5dUWk0').get()
data = doc.to_dict()
questions = data.get('questions', [])
answers = data.get('answers', [])

def get_earned(q, a):
    if not a: return 0.0
    if a.get('type') == 'mcq':
        correct = a.get('correct', False)
        attempts = a.get('attempts', 1)
        if correct:
            return q.get('marks') / 2.0 if attempts == 2 else q.get('marks')
        return 0.0
    return a.get('earnedMarks', 0.0)

for i in range(25):
    q = questions[i]
    a = answers[i]
    e = get_earned(q, a)
    print(f"Q{i+1}: {q.get('type')}, marks={q.get('marks')}, earned={e}")

print("Q1-20 earned:", sum(get_earned(questions[i], answers[i]) for i in range(20)))
print("Q1-21 earned:", sum(get_earned(questions[i], answers[i]) for i in range(21)))
